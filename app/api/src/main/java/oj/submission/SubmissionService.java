package oj.submission;

import oj.audit.AuditService;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.assignment.ProblemSnapshot;
import oj.assignment.Assignment;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提交服务：时间窗口 + 最大提交次数 + 幂等键防重放；
 * 计数器行锁保证并发下原子拒绝超限提交。
 */
@Service
public class SubmissionService {

    /** 判题终态（不含 PD 与 SE）。 */
    public static final Set<String> TERMINAL_CODES = Set.of(
            "CE", "AC", "WA", "RE", "TLE", "MLE", "OLE", "PE", "BSC");

    private final SubmissionRepository submissionRepository;
    private final SubmissionCounterRepository counterRepository;
    private final CounterProvisioner counterProvisioner;
    private final AssignmentService assignmentService;
    private final AuditService auditService;
    private final AccessGuard accessGuard;
    private final oj.judge.JudgeTaskService judgeTaskService;
    private final int maxCodeBytes;
    private final Clock clock;

    public SubmissionService(SubmissionRepository submissionRepository,
                             SubmissionCounterRepository counterRepository,
                             CounterProvisioner counterProvisioner,
                             AssignmentService assignmentService,
                             AuditService auditService,
                             AccessGuard accessGuard,
                             oj.judge.JudgeTaskService judgeTaskService,
                             @Value("${oj.submission.max-code-bytes:262144}") int maxCodeBytes,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.counterRepository = counterRepository;
        this.counterProvisioner = counterProvisioner;
        this.assignmentService = assignmentService;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
        this.judgeTaskService = judgeTaskService;
        this.maxCodeBytes = maxCodeBytes;
        this.clock = clock;
    }

    public record SubmitCommand(Long assignmentTargetId, Long problemId, String language,
                               String code, String idempotencyKey) {
    }

    /**
     * 学生提交。幂等键重复时返回既有提交（不消耗次数）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Submission submit(SubmitCommand command) {
        var user = accessGuard.requireStudent();
        Long studentId = user.studentId();
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少幂等键");
        }
        if (command.code() == null || command.code().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "代码不能为空");
        }
        if (command.code().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxCodeBytes) {
            throw new ApiException(ErrorCode.CODE_TOO_LARGE);
        }
        AssignmentTarget target = assignmentService.requireAccessibleTargetForStudent(
                studentId, command.assignmentTargetId());
        LocalDateTime now = LocalDateTime.now(clock);
        if (target.windowState(now) == AssignmentTarget.WindowState.NOT_STARTED) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_STARTED);
        }
        if (!target.isOpenAt(now)) {
            throw new ApiException(ErrorCode.WINDOW_CLOSED);
        }
        // 先串行化同一学生的提交；READ_COMMITTED 保证等待行锁后能读到刚提交的幂等记录。
        SubmissionCounter counter = lockOrCreateCounter(command.assignmentTargetId(), studentId);
        // 幂等检查必须在次数上限检查之前，允许已达上限的请求安全重试。
        Submission existing = submissionRepository
                .findByAssignmentTargetIdAndProblemIdAndStudentIdAndIdempotencyKey(
                        command.assignmentTargetId(), command.problemId(), studentId,
                        command.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            auditService.record(AuditActions.SUBMISSION_REPLAY_REJECTED, "SUBMISSION",
                    String.valueOf(existing.getId()),
                    Map.of("idempotencyKey", command.idempotencyKey()),
                    Map.of("replayed", true));
            return existing;
        }
        // 题目必须属于该作业（以快照为准）
        ProblemSnapshot snapshot = assignmentService.snapshots(target.getAssignmentId()).stream()
                .filter(s -> s.getProblemId().equals(command.problemId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "题目不属于该作业"));
        String language = command.language() == null ? "" : command.language().trim().toUpperCase();
        boolean allowed = java.util.Arrays.stream(snapshot.getLanguages().split(","))
                .map(String::trim)
                .anyMatch(language::equals);
        if (!allowed) {
            throw new ApiException(ErrorCode.LANGUAGE_NOT_ALLOWED);
        }
        if (counter.getAttemptCount() >= target.getMaxSubmissions()) {
            throw new ApiException(ErrorCode.SUBMISSION_LIMIT_EXCEEDED);
        }
        int attemptNo = counter.increment();
        Submission submission = submissionRepository.save(new Submission(
                command.assignmentTargetId(), command.problemId(), studentId,
                language, command.code(), attemptNo, command.idempotencyKey()));
        auditService.record(AuditActions.SUBMISSION_ACCEPTED, "SUBMISSION",
                String.valueOf(submission.getId()),
                null,
                Map.of("assignmentTargetId", command.assignmentTargetId(),
                        "problemId", command.problemId(),
                        "studentId", studentId,
                        "attemptNo", attemptNo));
        // Task 6：同一事务内创建 PENDING 判题任务与 Outbox 事件
        judgeTaskService.createTaskForSubmission(submission, snapshot);
        return submission;
    }

    public List<Submission> mySubmissions(Long studentId, Long assignmentTargetId, Long problemId) {
        if (problemId != null) {
            return submissionRepository.findByAssignmentTargetIdAndProblemIdAndStudentIdOrderByCreatedAtAsc(
                    assignmentTargetId, problemId, studentId);
        }
        return submissionRepository.findByAssignmentTargetIdAndStudentIdOrderByCreatedAtAsc(
                assignmentTargetId, studentId);
    }

    public List<Submission> submissionsOfTarget(Long assignmentTargetId) {
        return submissionRepository.findByAssignmentTargetIdOrderByIdAsc(assignmentTargetId);
    }

    public Submission requireSubmission(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提交不存在"));
    }

    private SubmissionCounter lockOrCreateCounter(Long targetId, Long studentId) {
        // 仅检查存在性，不能提前加载实体，否则加锁后仍可能读取一级缓存中的旧计数。
        if (counterRepository.existsById(new SubmissionCounter.Pk(targetId, studentId))) {
            return counterRepository.lockCounter(targetId, studentId)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        }
        // 幂等创建（REQUIRES_NEW 隔离，避免主事务被 duplicate 标记 rollback-only）
        counterProvisioner.provision(targetId, studentId);
        return counterRepository.lockCounter(targetId, studentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 教师查看目标班级的提交列表前必须通过授课关系授权（由控制器调用）。
     */
    public Assignment requirePublishedAssignment(Long assignmentId) {
        Assignment assignment = assignmentService.requireAssignment(assignmentId);
        if (assignment.getStatus() == Assignment.Status.DRAFT) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_PUBLISHED);
        }
        return assignment;
    }
}
