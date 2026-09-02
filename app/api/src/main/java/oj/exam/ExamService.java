package oj.exam;

import oj.audit.AuditService;
import oj.assignment.Assignment;
import oj.assignment.AssignmentRepository;
import oj.assignment.AssignmentTarget;
import oj.assignment.AssignmentTargetRepository;
import oj.auth.CurrentUser;
import oj.classroom.ClassroomService;
import oj.classroom.TeacherAssignment;
import oj.identity.AdminTotpRepository;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.submission.JudgeResult;
import oj.submission.JudgeResultRepository;
import oj.submission.Submission;
import oj.submission.SubmissionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 考试模式（Task 9，设计 13）：
 * - 锁定：EXAM 发布后经双人审批进入 exam_lock，冻结判题运行时标识与策略校验和；
 * - 双人审批：考试期间题目/成绩/配置修改需第二人批准（批准人须 TOTP 已确认），
 *   每次审批与放行均审计并触发告警动作（P1 接入 Task 8 监控）；
 * - 归档：考试结束导出不可变 JSONL 归档（含 Agent 签名身份、任务、快照版本、
 *   资源指标与写入时间），附 SHA-256 校验和，供离线复判（judge/rejudge）；
 * - 申诉复判：复判以新版本结果落库（版本单调），原/复判两版与差异清单保留在
 *   exam_appeal，不得自动采用任一版本，人工复核后 RESOLVED；
 * - 抽查：按随机种子抽取 ≥5% 提交，种子与选样留痕审计。
 */
@Service
public class ExamService {

    /** 审批动作。 */
    public static final String ACTION_LOCK = "LOCK";
    public static final String ACTION_CHANGE_TARGET_RULES = "CHANGE_TARGET_RULES";
    public static final String ACTION_PUBLISH = "PUBLISH";
    public static final String ACTION_WITHDRAW = "WITHDRAW";
    public static final String ACTION_CHANGE_GRADE = "CHANGE_GRADE";

    private final AssignmentRepository assignmentRepository;
    private final AssignmentTargetRepository targetRepository;
    private final ExamLockRepository lockRepository;
    private final ExamApprovalRepository approvalRepository;
    private final ExamAppealRepository appealRepository;
    private final ExamSpotCheckRepository spotCheckRepository;
    private final ExamArchiveRepository archiveRepository;
    private final SubmissionRepository submissionRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final oj.submission.TestcaseResultRepository testcaseResultRepository;
    private final oj.judge.JudgeTaskService judgeTaskService;
    private final AccessGuard accessGuard;
    private final ClassroomService classroomService;
    private final AdminTotpRepository totpRepository;
    private final AuditService auditService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;
    private final Path archiveDir;

    public ExamService(AssignmentRepository assignmentRepository,
                       AssignmentTargetRepository targetRepository,
                       ExamLockRepository lockRepository,
                       ExamApprovalRepository approvalRepository,
                       ExamAppealRepository appealRepository,
                       ExamSpotCheckRepository spotCheckRepository,
                       ExamArchiveRepository archiveRepository,
                       SubmissionRepository submissionRepository,
                       JudgeResultRepository judgeResultRepository,
                       oj.submission.TestcaseResultRepository testcaseResultRepository,
                       oj.judge.JudgeTaskService judgeTaskService,
                       AccessGuard accessGuard,
                       ClassroomService classroomService,
                       AdminTotpRepository totpRepository,
                       AuditService auditService,
                       com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                       Clock clock,
                       @Value("${oj.exam.archive-dir:/var/lib/oj/exam-archives}") String archiveDir) {
        this.assignmentRepository = assignmentRepository;
        this.targetRepository = targetRepository;
        this.lockRepository = lockRepository;
        this.approvalRepository = approvalRepository;
        this.appealRepository = appealRepository;
        this.spotCheckRepository = spotCheckRepository;
        this.archiveRepository = archiveRepository;
        this.submissionRepository = submissionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.testcaseResultRepository = testcaseResultRepository;
        this.judgeTaskService = judgeTaskService;
        this.accessGuard = accessGuard;
        this.classroomService = classroomService;
        this.totpRepository = totpRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.archiveDir = Path.of(archiveDir);
    }

    // ---------- 授权 ----------

    private Assignment authorizeExam(Long assignmentId) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "作业不存在"));
        if (assignment.getMode() != Assignment.Mode.EXAM) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "仅 EXAM 模式支持考试流程");
        }
        if (user.role() == oj.auth.AppUser.Role.ADMIN) {
            return assignment;
        }
        if (user.teacherId() == null || !user.teacherId().equals(assignment.getCreatedBy())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return assignment;
    }

    // ---------- 锁定与双人审批 ----------

    /**
     * EXAM 发布即自动锁定（设计 13.1 / 计划 Task 4"EXAM 发布后进入锁定状态"）：
     * 冻结判题运行时标识与策略校验和；此后一切修改须双人审批放行。
     */
    @Transactional
    public void autoLock(Long assignmentId, Long lockedBy) {
        if (lockRepository.findByAssignmentId(assignmentId).isPresent()) {
            return;
        }
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "作业不存在"));
        String runtimeIds = String.join(",",
                oj.judge.JudgeTaskService.runtimeIds().stream().sorted().toList());
        String policyChecksum = checksum(runtimeIds);
        lockRepository.save(new ExamLock(assignmentId, lockedBy, runtimeIds, policyChecksum, "EXAM 发布自动锁定"));
        assignment.markExamLocked();
        assignmentRepository.save(assignment);
        auditService.record(AuditActions.EXAM_LOCKED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("lockedBy", lockedBy, "runtimeIds", runtimeIds,
                        "policyChecksum", policyChecksum));
    }

    /** 发起一条修改类审批（第一人）；第二人批准后该动作放行。 */
    @Transactional
    public ExamApproval requestChange(Long assignmentId, String action, String reason) {
        authorizeExam(assignmentId);
        if (!List.of(ACTION_CHANGE_TARGET_RULES, ACTION_PUBLISH, ACTION_WITHDRAW,
                ACTION_CHANGE_GRADE).contains(action)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的审批动作");
        }
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        ExamApproval approval = approvalRepository.save(new ExamApproval(
                assignmentId, action, null, reason, user.appUserId()));
        auditService.record(AuditActions.EXAM_LOCK_REQUESTED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("requestedBy", user.appUserId(), "approvalId", approval.getId(),
                        "action", action));
        return approval;
    }

    /** 审批：请求人与批准人不得为同一人；批准人须持有已确认 TOTP（二次认证）。 */
    @Transactional
    public ExamApproval decide(Long approvalId, boolean approve, String reason) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        ExamApproval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "审批不存在"));
        if (!ExamApproval.PENDING.equals(approval.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "审批已处理");
        }
        if (user.appUserId().equals(approval.getRequestedBy())) {
            throw new ApiException(ErrorCode.EXAM_SELF_APPROVAL);
        }
        requireConfirmedTotp(user);
        if (approve) {
            approval.approve(user.appUserId());
            auditService.record(AuditActions.EXAM_APPROVAL_APPROVED, "ASSIGNMENT",
                    String.valueOf(approval.getAssignmentId()), null,
                    Map.of("approvalId", approvalId, "action", approval.getAction(),
                            "approver", user.appUserId(), "reason", reason == null ? "" : reason));
        } else {
            approval.reject(user.appUserId());
            auditService.record(AuditActions.EXAM_APPROVAL_REJECTED, "ASSIGNMENT",
                    String.valueOf(approval.getAssignmentId()), null,
                    Map.of("approvalId", approvalId, "action", approval.getAction(),
                            "approver", user.appUserId()));
        }
        return approval;
    }

    /** 二次认证：批准人必须完成 TOTP 绑定与确认（Task 5）。 */
    private void requireConfirmedTotp(CurrentUser user) {
        boolean confirmed = totpRepository.findByAppUserId(user.appUserId())
                .map(oj.identity.AdminTotp::isConfirmed)
                .orElse(false);
        if (!confirmed) {
            throw new ApiException(ErrorCode.MFA_ENROLLMENT_REQUIRED);
        }
    }

    private void executeLock(Long assignmentId, Long lockedBy, String reason) {
        Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        String runtimeIds = String.join(",", oj.judge.JudgeTaskService.runtimeIds().stream().sorted().toList());
        String policyChecksum = checksum(runtimeIds);
        lockRepository.save(new ExamLock(assignmentId, lockedBy, runtimeIds, policyChecksum, reason));
        assignment.markExamLocked();
        assignmentRepository.save(assignment);
        auditService.record(AuditActions.EXAM_LOCKED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("lockedBy", lockedBy, "runtimeIds", runtimeIds,
                        "policyChecksum", policyChecksum));
    }

    private String checksum(String data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("校验和计算失败", e);
        }
    }

    /**
     * 考试锁定后的修改闸门：未锁定（HOMEWORK）直接放行；
     * 已锁定则必须存在同动作的 APPROVED 审批（双人批准），
     * 未批准一律 EXAM_LOCKED（与 Task 4 语义一致），放行动作亦审计（告警）。
     */
    public void requireChangeAllowed(Long assignmentId, String action) {
        if (lockRepository.findByAssignmentId(assignmentId).isEmpty()) {
            return;
        }
        approvalRepository.findFirstByAssignmentIdAndActionAndStatusOrderByDecidedAtDesc(
                        assignmentId, action, ExamApproval.APPROVED)
                .orElseThrow(() -> new ApiException(ErrorCode.EXAM_LOCKED));
        auditService.record(AuditActions.EXAM_CHANGE_ALLOWED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("action", action));
    }

    public List<ExamApproval> approvals(Long assignmentId) {
        authorizeExam(assignmentId);
        return approvalRepository.findByAssignmentIdOrderByIdDesc(assignmentId);
    }

    public List<ExamAppeal> appeals(Long assignmentId) {
        authorizeExam(assignmentId);
        return appealRepository.findByAssignmentIdOrderByIdDesc(assignmentId);
    }

    public boolean isLocked(Long assignmentId) {
        return lockRepository.findByAssignmentId(assignmentId).isPresent();
    }

    // ---------- 不可变归档 ----------

    /** 考试结束导出不可变归档：提交、判题结果（含 Agent 身份/版本/指标/写入时间）与测试点明细。 */
    @Transactional
    public ExamArchive exportArchive(Long assignmentId) {
        authorizeExam(assignmentId);
        if (lockRepository.findByAssignmentId(assignmentId).isEmpty()) {
            throw new ApiException(ErrorCode.EXAM_NOT_LOCKED);
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (AssignmentTarget target : targetRepository.findByAssignmentIdOrderByIdAsc(assignmentId)) {
            for (Submission submission : submissionRepository
                    .findByAssignmentTargetIdOrderByIdAsc(target.getId())) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("submissionId", submission.getId());
                rec.put("assignmentTargetId", submission.getAssignmentTargetId());
                rec.put("studentId", submission.getStudentId());
                rec.put("problemId", submission.getProblemId());
                rec.put("attemptNo", submission.getAttemptNo());
                rec.put("language", submission.getLanguage());
                rec.put("code", submission.getCode());
                rec.put("judgeStatus", submission.getJudgeStatus());
                rec.put("submittedAt", submission.getCreatedAt().toString());
                judgeResultRepository.findBySubmissionId(submission.getId()).ifPresent(result -> {
                    Map<String, Object> jr = new LinkedHashMap<>();
                    jr.put("resultCode", result.getResultCode());
                    jr.put("normalizedScore", result.getNormalizedScore());
                    jr.put("agentId", result.getAgentId());
                    jr.put("resultVersion", result.getResultVersion());
                    jr.put("totalTimeMs", result.getTotalTimeMs());
                    jr.put("peakMemoryKb", result.getPeakMemoryKb());
                    jr.put("recordedAt", result.getCreatedAt().toString());
                    List<Map<String, Object>> tcs = new ArrayList<>();
                    for (oj.submission.TestcaseResult tcr : testcaseResultRepository
                            .findByJudgeResultIdOrderByTestcaseOrderAsc(result.getId())) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("order", tcr.getTestcaseOrder());
                        t.put("status", tcr.getStatus());
                        t.put("score", tcr.getScore());
                        t.put("timeMs", tcr.getTimeMs());
                        t.put("memoryKb", tcr.getMemoryKb());
                        tcs.add(t);
                    }
                    jr.put("testcases", tcs);
                    rec.put("judgeResult", jr);
                });
                records.add(rec);
            }
        }
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> rec : records) {
            try {
                jsonl.append(objectMapper.writeValueAsString(rec)).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("归档序列化失败", e);
            }
        }
        byte[] body = jsonl.toString().getBytes(StandardCharsets.UTF_8);
        String checksum = checksum(new String(body, StandardCharsets.UTF_8));
        try {
            Files.createDirectories(archiveDir);
            Path file = archiveDir.resolve("exam-" + assignmentId + "-"
                    + LocalDateTime.now(clock).toString().replace(':', '-') + ".jsonl");
            Files.writeString(file, jsonl.toString());
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha256"), checksum + "  " + file.getFileName());
        } catch (Exception e) {
            throw new IllegalStateException("归档写入失败", e);
        }
        ExamArchive archive = archiveRepository.save(new ExamArchive(assignmentId,
                "exam-" + assignmentId, checksum, records.size()));
        auditService.record(AuditActions.EXAM_ARCHIVE_EXPORTED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("checksum", checksum, "submissions", records.size()));
        return archive;
    }

    // ---------- 申诉复判 ----------

    @Transactional
    public ExamAppeal createAppeal(Long assignmentId, Long submissionId, String reason) {
        authorizeExam(assignmentId);
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提交不存在"));
        if (!submission.getAssignmentTargetId().equals(targetOf(assignmentId, submission.getAssignmentTargetId()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "提交不属于该考试");
        }
        JudgeResult original = judgeResultRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "该提交尚无判题结果"));
        ExamAppeal appeal = appealRepository.save(new ExamAppeal(assignmentId, submissionId,
                accessGuard.requireAdminOrTeacher().appUserId(), reason,
                original.getResultCode(), original.getNormalizedScore()));
        // 排队人工触发的复判任务（新版本结果；版本单调，证据保留）
        judgeTaskService.scheduleManualRejudge(submissionId);
        auditService.record(AuditActions.EXAM_APPEAL_CREATED, "SUBMISSION", String.valueOf(submissionId),
                null, Map.of("appealId", appeal.getId(), "reason", reason));
        return appeal;
    }

    private Long targetOf(Long assignmentId, Long targetId) {
        return targetRepository.findById(targetId)
                .filter(t -> t.getAssignmentId().equals(assignmentId))
                .map(AssignmentTarget::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "目标班级不属于该考试"));
    }

    /** 复判结果落库后（网关回调）：差异写入申诉单，状态 REJUDGED，等待人工复核。 */
    @Transactional
    public void applyRejudgeOutcome(Long submissionId, String code, BigDecimal score) {
        appealRepository.findFirstBySubmissionIdAndStatusOrderByCreatedAtDesc(submissionId, ExamAppeal.PENDING)
                .ifPresent(appeal -> {
                    appeal.applyRejudgeOutcome(code, score.setScale(2, RoundingMode.HALF_UP));
                    appealRepository.save(appeal);
                    auditService.record(AuditActions.EXAM_APPEAL_REJUDGED, "SUBMISSION",
                            String.valueOf(submissionId), null,
                            Map.of("appealId", appeal.getId(), "diffNote",
                                    appeal.getDiffNote() == null ? "" : appeal.getDiffNote()));
                });
    }

    /** 人工复核：采纳与否均不自动决定，须管理员明示（身份与依据写入审计）。 */
    @Transactional
    public ExamAppeal resolveAppeal(Long assignmentId, Long appealId, boolean adoptRejudged, String basis) {
        authorizeExam(assignmentId);
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        if (user.role() != oj.auth.AppUser.Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "申诉复核仅管理员执行");
        }
        ExamAppeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "申诉不存在"));
        if (!ExamAppeal.REJUDGED.equals(appeal.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "申诉尚未产生复判结果");
        }
        if (adoptRejudged) {
            reviseScoreInternal(appeal.getSubmissionId(), appeal.getRejudgedCode(),
                    appeal.getRejudgedScore(), "申诉复核采纳复判");
        } else {
            reviseScoreInternal(appeal.getSubmissionId(), appeal.getOriginalCode(),
                    appeal.getOriginalScore(), "申诉复核维持原判");
        }
        appeal.resolve(user.appUserId());
        appealRepository.save(appeal);
        auditService.record(AuditActions.EXAM_APPEAL_RESOLVED, "SUBMISSION",
                String.valueOf(appeal.getSubmissionId()), null,
                Map.of("appealId", appealId, "adoptRejudged", adoptRejudged,
                        "resolver", user.appUserId(), "basis", basis == null ? "" : basis));
        return appeal;
    }

    // ---------- 成绩修订（双人审批闸门） ----------

    @Transactional
    public JudgeResult reviseScore(Long assignmentId, Long submissionId, BigDecimal newScore, String reason) {
        authorizeExam(assignmentId);
        requireChangeAllowed(assignmentId, ACTION_CHANGE_GRADE);
        JudgeResult result = judgeResultRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题结果不存在"));
        JudgeResult revised = reviseScoreInternal(submissionId, result.getResultCode(),
                newScore.setScale(2, RoundingMode.HALF_UP), reason);
        auditService.record(AuditActions.EXAM_SCORE_REVISED, "SUBMISSION", String.valueOf(submissionId),
                Map.of("score", result.getNormalizedScore()),
                Map.of("score", revised.getNormalizedScore(), "reason", reason == null ? "" : reason));
        return revised;
    }

    private JudgeResult reviseScoreInternal(Long submissionId, String code, BigDecimal score, String reason) {
        JudgeResult result = judgeResultRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题结果不存在"));
        result.applyNewVersion(code, score, result.getTotalTimeMs(), result.getPeakMemoryKb(),
                "grade-revision", result.getResultVersion() + 1, result.getCaseDetails());
        return judgeResultRepository.saveAndFlush(result);
    }

    // ---------- 随机抽查（≥5%） ----------

    @Transactional
    public List<ExamSpotCheck> spotCheck(Long assignmentId) {
        authorizeExam(assignmentId);
        List<Long> submissionIds = new ArrayList<>();
        for (AssignmentTarget target : targetRepository.findByAssignmentIdOrderByIdAsc(assignmentId)) {
            submissionRepository.findByAssignmentTargetIdOrderByIdAsc(target.getId())
                    .forEach(s -> submissionIds.add(s.getId()));
        }
        if (submissionIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "该考试暂无提交");
        }
        String seed = UUID.randomUUID().toString();
        Random random = new Random(seed.hashCode());
        List<Long> shuffled = new ArrayList<>(submissionIds);
        java.util.Collections.shuffle(shuffled, random);
        int sampleSize = Math.max(1, (int) Math.ceil(shuffled.size() * 0.05));
        List<ExamSpotCheck> selected = new ArrayList<>();
        for (Long id : shuffled.subList(0, sampleSize)) {
            selected.add(spotCheckRepository.save(new ExamSpotCheck(assignmentId, id, seed)));
        }
        auditService.record(AuditActions.EXAM_SPOT_CHECK_CREATED, "ASSIGNMENT", String.valueOf(assignmentId),
                null, Map.of("seed", seed, "total", submissionIds.size(), "sampled", sampleSize));
        return selected;
    }
}
