package oj.assignment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import oj.analytics.AnalyticsService;
import oj.classroom.TeacherAssignment;
import oj.classroom.TeachingClass;
import oj.classroom.ClassroomService;
import oj.export.GradeExport;
import oj.export.GradeExportService;
import oj.problem.Problem;
import oj.problem.ProblemBank;
import oj.problem.ProblemService;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师工作流：授课班级、题库/题目维护、组卷、发布、目标班级规则、
 * 成绩分析与异步导出。
 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final ClassroomService classroomService;
    private final ProblemService problemService;
    private final AssignmentService assignmentService;
    private final AnalyticsService analyticsService;
    private final GradeExportService exportService;
    private final oj.export.ExportScheduler exportScheduler;
    private final AccessGuard accessGuard;
    private final java.time.Clock clock;

    public TeacherController(ClassroomService classroomService,
                             ProblemService problemService,
                             AssignmentService assignmentService,
                             AnalyticsService analyticsService,
                             GradeExportService exportService,
                             oj.export.ExportScheduler exportScheduler,
                             AccessGuard accessGuard,
                             java.time.Clock clock) {
        this.classroomService = classroomService;
        this.problemService = problemService;
        this.assignmentService = assignmentService;
        this.analyticsService = analyticsService;
        this.exportService = exportService;
        this.exportScheduler = exportScheduler;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    // ---------------- 请求体 ----------------

    public record BankRequest(@NotNull Long teachingClassId, @NotBlank String name, String description) {
    }

    public record TestcaseRequest(int orderNum, boolean sample, String input,
                                  String expectedOutput, BigDecimal score) {
    }

    public record ProblemRequest(@NotNull Long bankId, @NotBlank String code, @NotBlank String title,
                                 String description, List<String> languages,
                                 Integer timeLimitMs, Integer memoryLimitMb, Integer outputLimitKb,
                                 BigDecimal maxScore, List<TestcaseRequest> testcases) {
    }

    public record ProblemUpdateRequest(String title, String description, List<String> languages,
                                       Integer timeLimitMs, Integer memoryLimitMb, Integer outputLimitKb,
                                       List<TestcaseRequest> testcases) {
    }

    public record AssignmentRequest(@NotBlank String title, @NotNull Assignment.Mode mode,
                                    @NotNull List<CompositionItemDto> items) {
    }

    public record CompositionItemDto(@NotNull Long problemId, @NotNull BigDecimal weight) {
    }

    public record PublishRequest(@NotNull List<TargetRuleDto> targets) {
    }

    public record TargetRuleDto(@NotNull Long teachingClassId, LocalDateTime publishAt,
                                LocalDateTime deadline, int maxSubmissions,
                                String scoringRules) {
    }

    public record RulesRequest(LocalDateTime publishAt, LocalDateTime deadline,
                               int maxSubmissions, String scoringRules) {
    }

    public record ExportRequest(@NotNull Long assignmentTargetId, @NotNull GradeExport.Format format,
                                String filterStudentNo, String filterNameKeyword) {
    }

    // ---------------- 授课班级 ----------------

    @GetMapping("/classes")
    public List<Map<String, Object>> myClasses() {
        var user = accessGuard.requireTeacher();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TeacherAssignment ta : classroomService.myTeachingAssignments(user.teacherId())) {
            TeachingClass tc = classroomService.requireTeachingClass(ta.getTeachingClassId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assignmentId", ta.getId());
            item.put("role", ta.getRole().name());
            item.put("teachingClassId", tc.getId());
            item.put("code", tc.getCode());
            item.put("name", tc.getName());
            item.put("termId", tc.getTermId());
            item.put("courseId", tc.getCourseId());
            result.add(item);
        }
        return result;
    }

    // ---------------- 题库与题目 ----------------

    @PostMapping("/problem-banks")
    public ProblemBank createBank(@Valid @RequestBody BankRequest request) {
        return problemService.createBank(request.teachingClassId(), request.name(), request.description());
    }

    @GetMapping("/problem-banks")
    public List<ProblemBank> banks(@RequestParam Long teachingClassId) {
        return problemService.listBanks(teachingClassId);
    }

    @PostMapping("/problems")
    public Map<String, Object> createProblem(@Valid @RequestBody ProblemRequest request) {
        Problem problem = problemService.createProblem(request.bankId(), request.code(), request.title(),
                request.description(), request.languages(),
                nz(request.timeLimitMs(), 10000), nz(request.memoryLimitMb(), 256),
                nz(request.outputLimitKb(), 65536), nzDecimal(request.maxScore(), new BigDecimal("100.00")),
                toInputs(request.testcases()));
        return Map.of("id", problem.getId(), "version", problem.getVersion(),
                "status", problem.getStatus().name());
    }

    @PutMapping("/problems/{id}/publish")
    public Map<String, Object> publishProblem(@PathVariable Long id) {
        Problem problem = problemService.publishProblem(id);
        return Map.of("id", problem.getId(), "status", problem.getStatus().name());
    }

    @PutMapping("/problems/{id}")
    public Map<String, Object> updateProblem(@PathVariable Long id,
                                             @Valid @RequestBody ProblemUpdateRequest request) {
        Problem problem = problemService.updateProblem(id, request.title(), request.description(),
                request.languages(), nz(request.timeLimitMs(), 0), nz(request.memoryLimitMb(), 0),
                nz(request.outputLimitKb(), 0), toInputs(request.testcases()));
        return Map.of("id", problem.getId(), "version", problem.getVersion());
    }

    @GetMapping("/problems")
    public List<Map<String, Object>> problems(@RequestParam Long bankId) {
        accessGuard.requireAdminOrTeacher();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Problem p : problemService.listProblems(bankId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("code", p.getCode());
            item.put("title", p.getTitle());
            item.put("languages", p.getLanguages());
            item.put("status", p.getStatus().name());
            item.put("version", p.getVersion());
            result.add(item);
        }
        return result;
    }

    @GetMapping("/problems/{id}/testcases")
    public List<Map<String, Object>> testcases(@PathVariable Long id) {
        var user = accessGuard.requireAdminOrTeacher();
        Problem problem = problemService.requireAccessibleProblem(id, user);
        var set = problemService.requireLatestTestcaseSet(problem.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var tc : problemService.allTestcases(set.getId())) {
            // 教师可见隐藏用例；但绝不返回完整 expected_output 以外的判题内部数据
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderNum", tc.getOrderNum());
            item.put("sample", tc.isSample());
            item.put("score", tc.getScore());
            result.add(item);
        }
        return result;
    }

    // ---------------- 组卷与发布 ----------------

    @PostMapping("/assignments")
    public Map<String, Object> createAssignment(@Valid @RequestBody AssignmentRequest request) {
        List<AssignmentService.CompositionItem> items = request.items().stream()
                .map(i -> new AssignmentService.CompositionItem(i.problemId(), i.weight()))
                .toList();
        Assignment assignment = assignmentService.createAssignment(request.title(), request.mode(), items);
        return Map.of("id", assignment.getId(), "status", assignment.getStatus().name(),
                "mode", assignment.getMode().name());
    }

    @PutMapping("/assignments/{id}")
    public Map<String, Object> updateAssignment(@PathVariable Long id,
                                                @Valid @RequestBody AssignmentRequest request) {
        List<AssignmentService.CompositionItem> items = request.items().stream()
                .map(i -> new AssignmentService.CompositionItem(i.problemId(), i.weight()))
                .toList();
        Assignment assignment = assignmentService.updateAssignment(id, request.title(), request.mode(), items);
        return Map.of("id", assignment.getId(), "status", assignment.getStatus().name(),
                "mode", assignment.getMode().name());
    }

    @GetMapping("/assignments")
    public List<Map<String, Object>> myAssignments() {
        var user = accessGuard.requireTeacher();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Assignment a : assignmentService.myAssignments(user.teacherId())) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (AssignmentTarget t : assignmentService.targets(a.getId())) {
                targets.add(targetView(t));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("title", a.getTitle());
            item.put("mode", a.getMode().name());
            item.put("status", a.getStatus().name());
            item.put("targets", targets);
            result.add(item);
        }
        return result;
    }

    @GetMapping("/assignments/{id}")
    public Map<String, Object> assignment(@PathVariable Long id) {
        var user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = assignmentService.requireViewableAssignment(id, user);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssignmentProblem ap : assignmentService.composition(id)) {
            items.add(Map.of("problemId", ap.getProblemId(), "orderNum", ap.getOrderNum(),
                    "weight", ap.getWeight()));
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (AssignmentTarget t : assignmentService.targets(id)) {
            targets.add(targetView(t));
        }
        return Map.of("id", assignment.getId(), "title", assignment.getTitle(),
                "mode", assignment.getMode().name(), "status", assignment.getStatus().name(),
                "items", items, "targets", targets);
    }

    @PostMapping("/assignments/{id}/publish")
    public List<Map<String, Object>> publish(@PathVariable Long id, @Valid @RequestBody PublishRequest request) {
        Map<Long, AssignmentService.TargetRule> targets = new LinkedHashMap<>();
        for (TargetRuleDto dto : request.targets()) {
            targets.put(dto.teachingClassId(), new AssignmentService.TargetRule(
                    dto.publishAt(), dto.deadline(), dto.maxSubmissions(), dto.scoringRules()));
        }
        List<AssignmentTarget> created = assignmentService.publish(id, targets);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AssignmentTarget t : created) {
            result.add(targetView(t));
        }
        return result;
    }

    @PostMapping("/assignments/{id}/targets/{classId}/withdraw")
    public Map<String, Object> withdrawTarget(@PathVariable Long id, @PathVariable Long classId) {
        AssignmentTarget t = assignmentService.withdrawTarget(id, classId);
        return targetView(t);
    }

    @PostMapping("/assignments/{id}/targets/{classId}/collect")
    public Map<String, Object> collectTarget(@PathVariable Long id, @PathVariable Long classId) {
        AssignmentTarget t = assignmentService.collectTarget(id, classId);
        return targetView(t);
    }

    @PostMapping("/assignments/{id}/targets/{classId}/republish")
    public Map<String, Object> republishTarget(@PathVariable Long id, @PathVariable Long classId) {
        AssignmentTarget t = assignmentService.republishTarget(id, classId);
        return targetView(t);
    }

    @PutMapping("/assignments/{id}/targets/{classId}/rules")
    public Map<String, Object> updateRules(@PathVariable Long id, @PathVariable Long classId,
                                           @Valid @RequestBody RulesRequest request) {
        AssignmentTarget t = assignmentService.updateTargetRules(id, classId,
                new AssignmentService.TargetRule(request.publishAt(), request.deadline(),
                        request.maxSubmissions(), request.scoringRules()));
        return targetView(t);
    }

    // ---------------- 成绩分析 ----------------

    @GetMapping("/analytics/targets/{targetId}")
    public AnalyticsService.TargetAnalytics analytics(@PathVariable Long targetId) {
        return analyticsService.targetAnalyticsForTeacher(targetId);
    }

    // ---------------- 成绩导出 ----------------

    @PostMapping("/exports")
    public Map<String, Object> createExport(@Valid @RequestBody ExportRequest request) {
        GradeExport export = exportService.create(new GradeExportService.CreateCommand(
                request.assignmentTargetId(), request.format(),
                request.filterStudentNo(), request.filterNameKeyword()));
        exportScheduler.generateAsync(export.getId());
        return Map.of("taskId", export.getId(), "status", export.getStatus().name());
    }

    @GetMapping("/exports/{id}")
    public Map<String, Object> exportStatus(@PathVariable Long id) {
        var user = accessGuard.requireTeacher();
        GradeExport export = exportService.requireViewableExport(id, user.teacherId());
        return Map.of("taskId", export.getId(), "status", export.getStatus().name(),
                "matchCount", export.getMatchCount(),
                "checksum", export.getFileChecksum() == null ? "" : export.getFileChecksum());
    }

    @PostMapping("/exports/{id}/download-token")
    public Map<String, Object> downloadToken(@PathVariable Long id) {
        return exportService.issueDownloadToken(id);
    }

    @GetMapping("/exports/download")
    public ResponseEntity<ByteArrayResource> download(@RequestParam String token) {
        GradeExportService.DownloadResult result = exportService.download(token);
        String filename = result.storageKey();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        if (result.checksum() != null) {
            headers.set("X-File-Checksum", result.checksum());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(result.content()));
    }

    @PostMapping("/exports/{id}/revoke-tokens")
    public Map<String, Object> revokeTokens(@PathVariable Long id) {
        exportService.revokeTokens(id);
        return Map.of("ok", true);
    }

    // ---------------- 内部 ----------------

    private static int nz(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal nzDecimal(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private List<ProblemService.TestcaseInput> toInputs(List<TestcaseRequest> testcases) {
        if (testcases == null) {
            return null;
        }
        return testcases.stream()
                .map(t -> new ProblemService.TestcaseInput(t.orderNum(), t.sample(),
                        t.input(), t.expectedOutput(), t.score()))
                .toList();
    }

    private Map<String, Object> targetView(AssignmentTarget t) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("assignmentId", t.getAssignmentId());
        view.put("teachingClassId", t.getTeachingClassId());
        view.put("status", t.getStatus().name());
        view.put("publishAt", t.getPublishAt() == null ? null : t.getPublishAt().toString());
        view.put("deadline", t.getDeadline() == null ? null : t.getDeadline().toString());
        view.put("maxSubmissions", t.getMaxSubmissions());
        view.put("scoringRules", t.getScoringRules());
        view.put("version", t.getVersion());
        view.put("window", t.windowState(LocalDateTime.now(clock)).name());
        return view;
    }
}
