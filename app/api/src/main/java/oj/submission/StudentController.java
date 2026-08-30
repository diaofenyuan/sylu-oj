package oj.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import oj.analytics.AnalyticsService;
import oj.assignment.Assignment;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.assignment.ProblemSnapshot;
import oj.problem.ProblemService;
import oj.problem.Testcase;
import oj.shared.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学生工作流：查看所属班级已发布作业与公开样例、在窗口与次数内重复提交、
 * 查看自己的提交记录与成绩。不得看到其他学生数据、其他班级或未发布题目。
 */
@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final AssignmentService assignmentService;
    private final ProblemService problemService;
    private final SubmissionService submissionService;
    private final AnalyticsService analyticsService;
    private final oj.classroom.ClassroomService classroomService;
    private final oj.submission.SubmissionCounterRepository counterRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final AccessGuard accessGuard;

    public StudentController(AssignmentService assignmentService,
                             ProblemService problemService,
                             SubmissionService submissionService,
                             AnalyticsService analyticsService,
                             oj.classroom.ClassroomService classroomService,
                             oj.submission.SubmissionCounterRepository counterRepository,
                             JudgeResultRepository judgeResultRepository,
                             AccessGuard accessGuard) {
        this.assignmentService = assignmentService;
        this.problemService = problemService;
        this.submissionService = submissionService;
        this.analyticsService = analyticsService;
        this.classroomService = classroomService;
        this.counterRepository = counterRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.accessGuard = accessGuard;
    }

    public record SubmitRequest(@NotNull Long assignmentTargetId, @NotNull Long problemId,
                                @NotBlank String language, @NotBlank String code,
                                @NotBlank String idempotencyKey) {
    }

    /**
     * 我所属班级的已发布作业/考试（不含未发布与其他班级）。
     */
    @GetMapping("/assignments")
    public List<Map<String, Object>> myAssignments() {
        var user = accessGuard.requireStudent();
        var enrollment = classroomService.requireActiveEnrollmentAny(user.studentId());
        return assignmentsForEnrollment(enrollment);
    }

    private List<Map<String, Object>> assignmentsForEnrollment(oj.classroom.StudentEnrollment enrollment) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AssignmentTarget target : assignmentService.publishedTargetsForClass(enrollment.getTeachingClassId())) {
            Assignment assignment = assignmentService.requireAssignment(target.getAssignmentId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetId", target.getId());
            item.put("assignmentId", assignment.getId());
            item.put("title", assignment.getTitle());
            item.put("mode", assignment.getMode().name());
            item.put("publishAt", target.getPublishAt().toString());
            item.put("deadline", target.getDeadline().toString());
            item.put("maxSubmissions", target.getMaxSubmissions());
            item.put("attemptCount", counterRepository
                    .findById(new oj.submission.SubmissionCounter.Pk(target.getId(), enrollment.getStudentId()))
                    .map(oj.submission.SubmissionCounter::getAttemptCount).orElse(0));
            result.add(item);
        }
        return result;
    }

    /**
     * 目标班级的题目快照与公开样例（隐藏用例不暴露内容）。
     */
    @GetMapping("/targets/{targetId}/problems")
    public List<Map<String, Object>> targetProblems(@PathVariable Long targetId) {
        var user = accessGuard.requireStudent();
        AssignmentTarget target = assignmentService.requireAccessibleTargetForStudent(user.studentId(), targetId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProblemSnapshot snapshot : assignmentService.snapshots(target.getAssignmentId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problemId", snapshot.getProblemId());
            item.put("title", snapshot.getTitle());
            item.put("description", snapshot.getDescription());
            item.put("languages", snapshot.getLanguages());
            item.put("judgeConfig", snapshot.getJudgeConfig());
            List<Map<String, Object>> samples = new ArrayList<>();
            for (Testcase tc : problemService.sampleTestcases(snapshot.getTestcaseSetId())) {
                samples.add(Map.of("orderNum", tc.getOrderNum(), "input", tc.getInput()));
            }
            item.put("samples", samples);
            result.add(item);
        }
        return result;
    }

    @PostMapping("/submissions")
    public Map<String, Object> submit(@Valid @RequestBody SubmitRequest request) {
        Submission submission = submissionService.submit(new SubmissionService.SubmitCommand(
                request.assignmentTargetId(), request.problemId(), request.language(),
                request.code(), request.idempotencyKey()));
        return Map.of("submissionId", submission.getId(),
                "attemptNo", submission.getAttemptNo(),
                "judgeStatus", submission.getJudgeStatus());
    }

    @GetMapping("/submissions")
    public List<Map<String, Object>> mySubmissions(@RequestParam Long assignmentTargetId,
                                                   @RequestParam(required = false) Long problemId) {
        var user = accessGuard.requireStudent();
        assignmentService.requireAccessibleTargetForStudent(user.studentId(), assignmentTargetId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissionService.mySubmissions(user.studentId(), assignmentTargetId, problemId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("submissionId", s.getId());
            item.put("problemId", s.getProblemId());
            item.put("attemptNo", s.getAttemptNo());
            item.put("language", s.getLanguage());
            item.put("judgeStatus", s.getJudgeStatus());
            item.put("submittedAt", s.getCreatedAt().toString());
            item.put("idempotencyKey", s.getIdempotencyKey());
            judgeResultRepository.findBySubmissionId(s.getId()).ifPresent(jr -> {
                item.put("normalizedScore", jr.getNormalizedScore());
                item.put("totalTimeMs", jr.getTotalTimeMs());
            });
            result.add(item);
        }
        return result;
    }

    /**
     * 我自己的分析行：总分、通过率、提交次数、状态分布、班级排名。
     */
    @GetMapping("/analytics/targets/{targetId}")
    public AnalyticsService.StudentAnalyticsRow myAnalytics(@PathVariable Long targetId) {
        var user = accessGuard.requireStudent();
        return analyticsService.ownAnalytics(user.studentId(), targetId);
    }
}
