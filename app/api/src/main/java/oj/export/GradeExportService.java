package oj.export;

import oj.analytics.AnalyticsService;
import oj.analytics.AnalyticsService.StudentAnalyticsRow;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.classroom.ClassroomService;
import oj.classroom.Student;
import oj.classroom.StudentRepository;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 成绩导出服务：
 * - 异步任务状态机 QUEUED/GENERATING/READY/FAILED/EXPIRED，创建立即返回任务编号；
 * - XLSX 双工作表 / ZIP 双 CSV，用户可控单元格经公式注入消毒；
 * - 30 分钟单次下载授权，首次成功下载立即失效；
 * - 文件最多保留 24 小时，定时清理，失败告警并审计；
 * - 默认排除源代码、隐藏测试数据、编译器内部日志与其他班级数据。
 */
@Service
public class GradeExportService {

    private static final Logger log = LoggerFactory.getLogger(GradeExportService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GradeExportRepository exportRepository;
    private final GradeExportTokenRepository tokenRepository;
    private final AssignmentService assignmentService;
    private final AnalyticsService analyticsService;
    private final ClassroomService classroomService;
    private final StudentRepository studentRepository;
    private final oj.submission.SubmissionRepository submissionRepository;
    private final oj.submission.JudgeResultRepository judgeResultRepository;
    private final oj.submission.TestcaseResultRepository testcaseResultRepository;
    private final oj.submission.SubmissionService submissionService;
    private final AuditService auditService;
    private final AccessGuard accessGuard;
    private final Path exportDir;
    private final long tokenTtlMinutes;
    private final long fileRetentionHours;
    private final Clock clock;

    public GradeExportService(GradeExportRepository exportRepository,
                              GradeExportTokenRepository tokenRepository,
                              AssignmentService assignmentService,
                              AnalyticsService analyticsService,
                              ClassroomService classroomService,
                              StudentRepository studentRepository,
                              oj.submission.SubmissionRepository submissionRepository,
                              oj.submission.JudgeResultRepository judgeResultRepository,
                              oj.submission.TestcaseResultRepository testcaseResultRepository,
                              oj.submission.SubmissionService submissionService,
                              AuditService auditService,
                              AccessGuard accessGuard,
                              @Value("${oj.export.dir:var/exports}") String exportDir,
                              @Value("${oj.export.token-ttl-minutes:30}") long tokenTtlMinutes,
                              @Value("${oj.export.file-retention-hours:24}") long fileRetentionHours,
                              Clock clock) {
        this.exportRepository = exportRepository;
        this.tokenRepository = tokenRepository;
        this.assignmentService = assignmentService;
        this.analyticsService = analyticsService;
        this.classroomService = classroomService;
        this.studentRepository = studentRepository;
        this.submissionRepository = submissionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.testcaseResultRepository = testcaseResultRepository;
        this.submissionService = submissionService;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
        this.exportDir = Path.of(exportDir);
        this.tokenTtlMinutes = tokenTtlMinutes;
        this.fileRetentionHours = fileRetentionHours;
        this.clock = clock;
    }

    public record CreateCommand(Long assignmentTargetId, GradeExport.Format format,
                                String filterStudentNo, String filterNameKeyword) {
    }

    /**
     * 创建导出任务：立即返回任务编号（QUEUED），异步生成。
     */
    @Transactional
    public GradeExport create(CreateCommand command) {
        var user = accessGuard.requireAdminOrTeacher();
        AssignmentTarget target = assignmentService.requireTargetById(command.assignmentTargetId());
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), target.getTeachingClassId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        GradeExport export = exportRepository.save(new GradeExport(
                user.isTeacher() ? user.teacherId() : 0L,
                command.assignmentTargetId(), command.format(),
                blankToNull(command.filterStudentNo()), blankToNull(command.filterNameKeyword()),
                now, now.plusHours(fileRetentionHours)));
        auditService.record(AuditActions.EXPORT_REQUESTED, "GRADE_EXPORT", String.valueOf(export.getId()),
                null, java.util.Map.of(
                        "assignmentTargetId", command.assignmentTargetId(),
                        "format", command.format().name(),
                        "filterStudentNo", command.filterStudentNo() == null ? "" : command.filterStudentNo(),
                        "filterNameKeyword", command.filterNameKeyword() == null ? "" : command.filterNameKeyword()));
        return export;
    }

    /**
     * 同步生成（供异步 worker 与测试调用）。失败转入 FAILED，仅记录通用错误码。
     */
    @Transactional
    public void generate(Long exportId) {
        GradeExport export = exportRepository.findById(exportId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (export.getStatus() != GradeExport.Status.QUEUED
                && export.getStatus() != GradeExport.Status.GENERATING) {
            return;
        }
        export.markGenerating();
        try {
            doGenerate(export);
        } catch (Exception e) {
            log.error("导出任务 {} 生成失败", exportId, e);
            export.markFailed("EXPORT_GENERATION_FAILED");
            auditService.record(AuditActions.EXPORT_FAILED, "GRADE_EXPORT", String.valueOf(exportId),
                    null, java.util.Map.of("reason", e.getClass().getSimpleName()));
            throw e instanceof ApiException apiEx ? apiEx : new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void doGenerate(GradeExport export) throws IOException {
        AssignmentTarget target = assignmentService.requireTargetById(export.getAssignmentTargetId());
        AnalyticsService.TargetAnalytics analytics = analyticsService.buildAnalytics(target);
        List<StudentAnalyticsRow> rows = analytics.rows();

        // 筛选只能缩小已授权目标班级范围；空结果正常返回
        List<StudentAnalyticsRow> filtered = rows.stream()
                .filter(r -> export.getFilterStudentNo() == null
                        || r.studentNo().equals(export.getFilterStudentNo()))
                .filter(r -> export.getFilterNameKeyword() == null
                        || r.name() != null && r.name().contains(export.getFilterNameKeyword()))
                .toList();

        // 题目标签（题号-题名，来自作业快照）
        List<oj.assignment.AssignmentProblem> composition =
                assignmentService.composition(target.getAssignmentId());
        Map<Long, Integer> problemOrder = new LinkedHashMap<>();
        Map<Long, String> problemTitles = new LinkedHashMap<>();
        for (oj.assignment.ProblemSnapshot snapshot : assignmentService.snapshots(target.getAssignmentId())) {
            composition.stream()
                    .filter(ap -> ap.getProblemId().equals(snapshot.getProblemId()))
                    .findFirst()
                    .ifPresent(ap -> {
                        problemOrder.put(snapshot.getProblemId(), ap.getOrderNum());
                        problemTitles.put(snapshot.getProblemId(),
                                "P" + ap.getOrderNum() + ":" + snapshot.getTitle());
                    });
        }
        List<String> labels = problemTitles.values().stream()
                .sorted(java.util.Comparator.comparingInt(l -> Integer.parseInt(l.substring(1, l.indexOf(':')))))
                .toList();

        // 每位学生每题最高有效提交分（按快照题目维度）
        List<oj.submission.Submission> submissions = submissionService.submissionsOfTarget(target.getId());
        Map<Long, Map<Long, BigDecimal>> bestScores = new LinkedHashMap<>();
        for (oj.submission.Submission s : submissions) {
            if (!oj.submission.SubmissionService.TERMINAL_CODES.contains(s.getJudgeStatus())) {
                continue;
            }
            oj.submission.JudgeResult result = judgeResultRepository.findBySubmissionId(s.getId()).orElse(null);
            if (result == null) {
                continue;
            }
            bestScores.computeIfAbsent(s.getStudentId(), k -> new LinkedHashMap<>())
                    .merge(s.getProblemId(), result.getNormalizedScore(), BigDecimal::max);
        }

        List<ExportFileWriter.SummaryRow> summary = new ArrayList<>();
        for (StudentAnalyticsRow row : filtered) {
            List<String> perProblem = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : problemOrder.entrySet()) {
                BigDecimal best = bestScores
                        .getOrDefault(row.studentId(), Map.of())
                        .get(entry.getKey());
                perProblem.add(best == null ? null : best.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            summary.add(new ExportFileWriter.SummaryRow(row.rank(), row.studentNo(), row.name(),
                    row.totalScore(), row.passRate(), row.submissionCount(),
                    formatDistribution(row.statusDistribution()), perProblem));
        }
        summary.sort(Comparator.comparingInt(ExportFileWriter.SummaryRow::rank)
                .thenComparing(ExportFileWriter.SummaryRow::studentNo));

        // 提交明细：学号、题目顺序、提交时间升序
        Map<Long, Student> students = new LinkedHashMap<>();
        for (StudentAnalyticsRow row : filtered) {
            studentRepository.findById(row.studentId()).ifPresent(st -> students.put(row.studentId(), st));
        }
        List<ExportFileWriter.DetailRow> details = new ArrayList<>();
        for (oj.submission.Submission s : submissions) {
            boolean inScope = filtered.stream().anyMatch(r -> r.studentId().equals(s.getStudentId()));
            if (!inScope) {
                continue;
            }
            oj.submission.JudgeResult result = judgeResultRepository.findBySubmissionId(s.getId()).orElse(null);
            BigDecimal score = result == null ? BigDecimal.ZERO : result.getNormalizedScore();
            long timeMs = result == null ? 0 : result.getTotalTimeMs();
            long memKb = result == null ? 0 : result.getPeakMemoryKb();
            long passed = 0;
            long total = 0;
            if (result != null) {
                List<oj.submission.TestcaseResult> tcs =
                        testcaseResultRepository.findByJudgeResultIdOrderByTestcaseOrderAsc(result.getId());
                total = tcs.size();
                passed = tcs.stream().filter(t -> "AC".equals(t.getStatus())).count();
            }
            Student student = students.get(s.getStudentId());
            details.add(new ExportFileWriter.DetailRow(
                    s.getStudentId().toString(),
                    student == null ? "" : student.getName(),
                    problemTitles.getOrDefault(s.getProblemId(), "P" + s.getProblemId()),
                    s.getAttemptNo(),
                    TIME_FORMAT.format(s.getCreatedAt()),
                    s.getLanguage(),
                    s.getJudgeStatus(),
                    score.setScale(2, RoundingMode.HALF_UP),
                    passed, total, timeMs, memKb));
        }
        details.sort(Comparator.comparing((ExportFileWriter.DetailRow d) -> detailStudentKey(d))
                .thenComparing(ExportFileWriter.DetailRow::problemLabel)
                .thenComparing(ExportFileWriter.DetailRow::attemptNo));

        ExportFileWriter.ExportDataset dataset = new ExportFileWriter.ExportDataset(labels, summary, details);
        byte[] bytes = export.getFormat() == GradeExport.Format.XLSX
                ? ExportFileWriter.writeXlsx(dataset)
                : ExportFileWriter.writeCsvZip(dataset);
        String extension = export.getFormat() == GradeExport.Format.XLSX ? ".xlsx" : ".zip";
        String storageKey = UUID.randomUUID().toString().replace("-", "") + extension;
        Files.createDirectories(exportDir);
        Path file = exportDir.resolve(storageKey);
        Files.write(file, bytes);
        export.markReady(filtered.size(), storageKey, sha256(bytes));
        auditService.record(AuditActions.EXPORT_GENERATED, "GRADE_EXPORT", String.valueOf(export.getId()),
                null, java.util.Map.of("matchCount", filtered.size(), "format", export.getFormat().name()));
    }

    private String detailStudentKey(ExportFileWriter.DetailRow d) {
        return d.studentNo();
    }

    private String formatDistribution(Map<String, Long> dist) {
        if (dist.isEmpty()) {
            return "";
        }
        return dist.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    // ---------------- 下载授权 ----------------

    /**
     * 重新签发 30 分钟单次下载授权：必须重新通过目标班级权限校验。
     */
    @Transactional
    public java.util.Map<String, Object> issueDownloadToken(Long exportId) {
        var user = accessGuard.requireAdminOrTeacher();
        GradeExport export = exportRepository.findById(exportId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        AssignmentTarget target = assignmentService.requireTargetById(export.getAssignmentTargetId());
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), target.getTeachingClassId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!export.isFileAlive(now)) {
            throw export.getStatus() == GradeExport.Status.READY
                    ? new ApiException(ErrorCode.EXPORT_EXPIRED)
                    : new ApiException(ErrorCode.EXPORT_NOT_READY);
        }
        String token = randomToken();
        GradeExportToken entity = tokenRepository.save(new GradeExportToken(
                exportId, sha256(token.getBytes(StandardCharsets.UTF_8)),
                export.getRequestedBy(), now, now.plusMinutes(tokenTtlMinutes)));
        auditService.record(AuditActions.EXPORT_TOKEN_ISSUED, "GRADE_EXPORT_TOKEN",
                String.valueOf(entity.getId()), null,
                java.util.Map.of("gradeExportId", exportId, "expiresAt", entity.getExpiresAt().toString()));
        return java.util.Map.of("token", token, "expiresAt", entity.getExpiresAt().toString());
    }

    /**
     * 单次下载：首次成功后令牌立即失效；过期/已用/撤销令牌一律拒绝。
     */
    @Transactional
    public record DownloadResult(byte[] content, String storageKey, String checksum) {
    }

    @Transactional
    public DownloadResult download(String token) {
        var user = accessGuard.requireAdminOrTeacher();
        GradeExportToken entity = tokenRepository.findByTokenHash(
                        sha256(token.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(() -> new ApiException(ErrorCode.EXPORT_TOKEN_INVALID));
        GradeExport export = exportRepository.findById(entity.getGradeExportId())
                .orElseThrow(() -> new ApiException(ErrorCode.EXPORT_TOKEN_INVALID));
        if (user.isTeacher() && !entity.getIssuedBy().equals(user.teacherId())) {
            throw new ApiException(ErrorCode.EXPORT_TOKEN_INVALID);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (entity.getStatus() == GradeExportToken.Status.USED) {
            throw new ApiException(ErrorCode.EXPORT_TOKEN_USED);
        }
        if (entity.getStatus() == GradeExportToken.Status.REVOKED) {
            throw new ApiException(ErrorCode.EXPORT_TOKEN_INVALID);
        }
        if (entity.getStatus() == GradeExportToken.Status.EXPIRED
                || entity.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.EXPORT_TOKEN_EXPIRED);
        }
        if (export.getStatus() != GradeExport.Status.READY || !export.isFileAlive(now)) {
            throw new ApiException(ErrorCode.EXPORT_EXPIRED);
        }
        // 首次成功下载前先标记失效（同事务），并发重复下载只有一个成功
        entity.markUsed();
        Path file = exportDir.resolve(export.getStorageKey());
        if (!Files.exists(file)) {
            throw new ApiException(ErrorCode.EXPORT_FILE_MISSING);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.EXPORT_FILE_MISSING);
        }
        auditService.record(AuditActions.EXPORT_DOWNLOADED, "GRADE_EXPORT", String.valueOf(export.getId()),
                null, java.util.Map.of("tokenId", entity.getId()));
        return new DownloadResult(content, export.getStorageKey(), export.getFileChecksum());
    }

    @Transactional
    public void revokeTokens(Long exportId) {
        accessGuard.requireAdminOrTeacher();
        List<GradeExportToken> tokens = tokenRepository.findByGradeExportId(exportId);
        for (GradeExportToken token : tokens) {
            if (token.getStatus() == GradeExportToken.Status.ACTIVE) {
                token.revoke();
                auditService.record(AuditActions.EXPORT_TOKEN_REVOKED, "GRADE_EXPORT_TOKEN",
                        String.valueOf(token.getId()), null, null);
            }
        }
    }

    // ---------------- 状态查询与清理 ----------------

    public GradeExport requireViewableExport(Long exportId, Long teacherId) {
        return exportRepository.findByIdAndRequestedBy(exportId, teacherId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    public List<GradeExport> myExports(Long teacherId) {
        return exportRepository.findByRequestedByOrderByIdDesc(teacherId);
    }

    /**
     * 定时清理：过期文件转 EXPIRED 并删除；过期令牌转 EXPIRED；失败告警并审计。
     */
    @Transactional
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 令牌过期
        for (GradeExportToken token : tokenRepository
                .findByStatusAndExpiresAtBefore(GradeExportToken.Status.ACTIVE, now)) {
            token.markExpired();
            auditService.record(AuditActions.EXPORT_TOKEN_EXPIRED, "GRADE_EXPORT_TOKEN",
                    String.valueOf(token.getId()), null, null);
        }
        // 文件过期
        for (GradeExport export : exportRepository
                .findByStatusAndExpiresAtBefore(GradeExport.Status.READY, now)) {
            try {
                Files.deleteIfExists(exportDir.resolve(export.getStorageKey()));
            } catch (Exception e) {
                log.error("导出文件清理失败: {}", export.getStorageKey(), e);
                auditService.record(AuditActions.EXPORT_CLEANUP_FAILED, "GRADE_EXPORT",
                        String.valueOf(export.getId()), null,
                        java.util.Map.of("reason", e.getClass().getSimpleName()));
                continue;
            }
            export.markExpired();
            auditService.record(AuditActions.EXPORT_FILE_EXPIRED, "GRADE_EXPORT",
                    String.valueOf(export.getId()), null, null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
