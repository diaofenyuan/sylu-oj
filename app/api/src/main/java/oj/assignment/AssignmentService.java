package oj.assignment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import oj.audit.AuditService;
import oj.shared.AuditActions;
import oj.classroom.ClassroomService;
import oj.classroom.TeachingClass;
import oj.classroom.TeacherAssignment;
import oj.auth.CurrentUser;
import oj.problem.Problem;
import oj.problem.ProblemService;
import oj.problem.TestcaseSet;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业服务：组卷（权重和恰为 100）、发布到多个目标班级（逐班校验授课关系、
 * 生成共享题目快照、独立规则）、受控修改（HOMEWORK）与锁定（EXAM）。
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentTargetRepository targetRepository;
    private final ProblemSnapshotRepository snapshotRepository;
    private final ProblemService problemService;
    private final ClassroomService classroomService;
    private final AuditService auditService;
    private final AccessGuard accessGuard;
    private final ObjectMapper objectMapper;
    private final oj.exam.ExamService examService;
    private final Clock clock;

    public AssignmentService(AssignmentRepository assignmentRepository,
                             AssignmentProblemRepository assignmentProblemRepository,
                             AssignmentTargetRepository targetRepository,
                             ProblemSnapshotRepository snapshotRepository,
                             ProblemService problemService,
                             ClassroomService classroomService,
                             AuditService auditService,
                             AccessGuard accessGuard,
                             ObjectMapper objectMapper,
                             oj.exam.ExamService examService,
                             Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.assignmentProblemRepository = assignmentProblemRepository;
        this.targetRepository = targetRepository;
        this.snapshotRepository = snapshotRepository;
        this.problemService = problemService;
        this.classroomService = classroomService;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
        this.examService = examService;
        this.clock = clock;
    }

    public record CompositionItem(Long problemId, BigDecimal weight) {
    }

    public record TargetRule(LocalDateTime publishAt, LocalDateTime deadline,
                             int maxSubmissions, String scoringRules) {
    }

    // ---------------- 组卷 ----------------

    @Transactional
    public Assignment createAssignment(String title, Assignment.Mode mode, List<CompositionItem> items) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        if (items == null || items.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_COMPOSITION);
        }
        validateWeights(items);
        Set<Long> problemIds = new HashSet<>();
        for (CompositionItem item : items) {
            if (!problemIds.add(item.problemId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "作业内题目重复");
            }
        }
        long publishedCount = problemService.countPublishedByIds(problemIds);
        if (publishedCount != problemIds.size()) {
            throw new ApiException(ErrorCode.PROBLEM_NOT_PUBLISHED);
        }
        // 教师必须能通过自己授课班级的题库访问全部题目
        if (user.isTeacher()) {
            for (Long problemId : problemIds) {
                problemService.requireAccessibleProblem(problemId, user);
            }
        }
        Long creatorId = user.isTeacher() ? user.teacherId() : 0L;
        Assignment assignment = assignmentRepository.save(new Assignment(title, mode, creatorId));
        int order = 1;
        for (CompositionItem item : items) {
            assignmentProblemRepository.save(new AssignmentProblem(
                    assignment.getId(), item.problemId(), order++, item.weight()));
        }
        auditService.record(AuditActions.ASSIGNMENT_CREATED, "ASSIGNMENT",
                String.valueOf(assignment.getId()),
                null,
                Map.of("title", title, "mode", mode.name(),
                        "itemCount", items.size(), "createdBy", creatorId));
        return assignment;
    }

    /**
     * 更新草稿试卷：仅创建者（或管理员）可改，且仅 DRAFT 状态允许；
     * 组卷明细整体重写，校验规则与创建一致。
     */
    @Transactional
    public Assignment updateAssignment(Long assignmentId, String title, Assignment.Mode mode,
                                       List<CompositionItem> items) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        requireCreator(assignment, user);
        if (assignment.getStatus() != Assignment.Status.DRAFT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "仅草稿状态的试卷可以修改");
        }
        if (items == null || items.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_COMPOSITION);
        }
        validateWeights(items);
        Set<Long> problemIds = new HashSet<>();
        for (CompositionItem item : items) {
            if (!problemIds.add(item.problemId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "作业内题目重复");
            }
        }
        long publishedCount = problemService.countPublishedByIds(problemIds);
        if (publishedCount != problemIds.size()) {
            throw new ApiException(ErrorCode.PROBLEM_NOT_PUBLISHED);
        }
        if (user.isTeacher()) {
            for (Long problemId : problemIds) {
                problemService.requireAccessibleProblem(problemId, user);
            }
        }
        assignment.updateComposition(title, mode);
        assignmentProblemRepository.deleteByAssignmentId(assignmentId);
        int order = 1;
        for (CompositionItem item : items) {
            assignmentProblemRepository.save(new AssignmentProblem(
                    assignmentId, item.problemId(), order++, item.weight()));
        }
        auditService.record(AuditActions.ASSIGNMENT_UPDATED, "ASSIGNMENT",
                String.valueOf(assignmentId),
                null,
                Map.of("title", title, "mode", mode.name(), "itemCount", items.size()));
        return assignment;
    }

    private void validateWeights(List<CompositionItem> items) {        BigDecimal sum = BigDecimal.ZERO;
        for (CompositionItem item : items) {
            BigDecimal weight = item.weight();
            if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0
                    || weight.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "题目权重必须在 (0, 100] 内");
            }
            sum = sum.add(weight);
        }
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new ApiException(ErrorCode.WEIGHT_NOT_100, "题目权重之和为 " + sum + "，必须恰为 100");
        }
    }

    // ---------------- 发布 ----------------

    @Transactional
    public List<AssignmentTarget> publish(Long assignmentId, Map<Long, TargetRule> targetsByClass) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        if (assignment.getStatus() == Assignment.Status.WITHDRAWN) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_PUBLISHED, "作业已撤回，不能再发布");
        }
        if (assignment.getStatus() == Assignment.Status.PUBLISHED && assignment.getMode() == Assignment.Mode.EXAM) {
            // Task 9：锁定后的再次发布/重发布须双人审批放行
            examService.requireChangeAllowed(assignmentId, oj.exam.ExamService.ACTION_PUBLISH);
        }
        if (targetsByClass == null || targetsByClass.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "至少选择一个目标班级");
        }
        if (user.isTeacher()) {
            requireCreator(assignment, user);
        }
        // 逐个校验目标教学班的授课关系（主讲或管理员才能发布）
        for (Long classId : targetsByClass.keySet()) {
            if (user.isTeacher()) {
                classroomService.requirePrimaryAssignment(user.teacherId(), classId);
            }
        }
        // 首次发布生成共享题目快照
        if (assignment.getStatus() == Assignment.Status.DRAFT) {
            createSnapshots(assignment);
        }
        List<AssignmentTarget> created = new ArrayList<>();
        for (Map.Entry<Long, TargetRule> entry : targetsByClass.entrySet()) {
            Long classId = entry.getKey();
            TargetRule rule = entry.getValue();
            if (targetRepository.findByAssignmentIdAndTeachingClassId(assignmentId, classId).isPresent()) {
                throw new ApiException(ErrorCode.DUPLICATE_TARGET, "目标班级已存在");
            }
            validateRule(rule);
            AssignmentTarget target = targetRepository.save(new AssignmentTarget(
                    assignmentId, classId, rule.publishAt(), rule.deadline(),
                    rule.maxSubmissions(), rule.scoringRules()));
            created.add(target);
            auditService.record(AuditActions.ASSIGNMENT_PUBLISHED, "ASSIGNMENT_TARGET",
                    String.valueOf(target.getId()),
                    null,
                    Map.of("assignmentId", assignmentId, "teachingClassId", classId,
                            "publishAt", strOrEmpty(rule.publishAt()),
                            "deadline", strOrEmpty(rule.deadline()),
                            "maxSubmissions", rule.maxSubmissions(),
                            "mode", assignment.getMode().name()));
        }
        assignment.publish();
        // Task 9：EXAM 发布即自动锁定（冻结判题运行时），此后修改须双人审批放行
        if (assignment.getMode() == Assignment.Mode.EXAM) {
            examService.autoLock(assignmentId, user.appUserId());
        }
        return created;
    }

    private void validateRule(TargetRule rule) {
        validateTimes(rule.publishAt(), rule.deadline());
        if (rule.maxSubmissions() <= 0 || rule.maxSubmissions() > 1000) {
            throw new ApiException(ErrorCode.TARGET_RULE_INVALID, "最大提交次数必须在 [1, 1000] 内");
        }
        if (rule.scoringRules() != null && rule.scoringRules().length() > 512) {
            throw new ApiException(ErrorCode.TARGET_RULE_INVALID, "计分规则过长");
        }
    }

    /** 时间窗口可留空（NULL 表示不限制）；两者均提供时截止必须晚于发布。 */
    private void validateTimes(LocalDateTime publishAt, LocalDateTime deadline) {
        if (publishAt != null && deadline != null && !deadline.isAfter(publishAt)) {
            throw new ApiException(ErrorCode.TARGET_RULE_INVALID, "截止时间必须晚于发布时间");
        }
    }

    private void createSnapshots(Assignment assignment) {
        List<AssignmentProblem> composition =
                assignmentProblemRepository.findByAssignmentIdOrderByOrderNumAsc(assignment.getId());
        for (AssignmentProblem item : composition) {
            Problem problem = problemService.requireProblem(item.getProblemId());
            TestcaseSet testcaseSet = problemService.requireLatestTestcaseSet(problem.getId());
            String judgeConfig = judgeConfigJson(problem);
            String checksum = checksum(problem, testcaseSet);
            snapshotRepository.save(new ProblemSnapshot(
                    assignment.getId(), problem.getId(), problem.getVersion(),
                    testcaseSet.getId(), problem.getTitle(), problem.getDescription(),
                    problem.getLanguages(), judgeConfig, checksum));
        }
    }

    private String judgeConfigJson(Problem problem) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "timeLimitMs", problem.getTimeLimitMs(),
                    "memoryLimitMb", problem.getMemoryLimitMb(),
                    "outputLimitKb", problem.getOutputLimitKb(),
                    "maxScore", problem.getMaxScore()));
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String checksum(Problem problem, TestcaseSet testcaseSet) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = problem.getId() + "|" + problem.getVersion() + "|"
                    + problem.getTitle() + "|" + problem.getDescription() + "|"
                    + problem.getLanguages() + "|" + problem.getTimeLimitMs() + "|"
                    + problem.getMemoryLimitMb() + "|" + problem.getOutputLimitKb() + "|"
                    + problem.getMaxScore() + "|" + testcaseSet.getId();
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---------------- 目标班级规则 ----------------

    @Transactional
    public AssignmentTarget updateTargetRules(Long assignmentId, Long teachingClassId, TargetRule newRule) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        if (user.isTeacher()) {
            requireCreator(assignment, user);
            classroomService.requirePrimaryAssignment(user.teacherId(), teachingClassId);
        }
        if (assignment.isExamLocked()) {
            examService.requireChangeAllowed(assignmentId, oj.exam.ExamService.ACTION_CHANGE_TARGET_RULES);
        }
        validateTimes(newRule.publishAt(), newRule.deadline());
        if (newRule.maxSubmissions() > 1000) {
            throw new ApiException(ErrorCode.TARGET_RULE_INVALID, "最大提交次数必须在 [1, 1000] 内");
        }
        AssignmentTarget target = requireTarget(assignmentId, teachingClassId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("publishAt", strOrEmpty(target.getPublishAt()));
        before.put("deadline", strOrEmpty(target.getDeadline()));
        before.put("maxSubmissions", target.getMaxSubmissions());
        before.put("scoringRules", target.getScoringRules());
        before.put("version", target.getVersion());
        target.updateRules(newRule.publishAt(), newRule.deadline(),
                newRule.maxSubmissions(), newRule.scoringRules());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("publishAt", strOrEmpty(target.getPublishAt()));
        after.put("deadline", strOrEmpty(target.getDeadline()));
        after.put("maxSubmissions", target.getMaxSubmissions());
        after.put("scoringRules", target.getScoringRules());
        after.put("version", target.getVersion());
        auditService.record(AuditActions.TARGET_RULES_UPDATED, "ASSIGNMENT_TARGET",
                String.valueOf(target.getId()), before, after);
        return target;
    }

    /**
     * 立即收卷：把目标班级的截止时间置为当前时刻，此后学生不可再提交（仍可查看与成绩）。
     * EXAM 已锁定须双人审批放行。
     */
    @Transactional
    public AssignmentTarget collectTarget(Long assignmentId, Long teachingClassId) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        if (user.isTeacher()) {
            requireCreator(assignment, user);
            classroomService.requirePrimaryAssignment(user.teacherId(), teachingClassId);
        }
        if (assignment.isExamLocked()) {
            examService.requireChangeAllowed(assignmentId, oj.exam.ExamService.ACTION_CHANGE_TARGET_RULES);
        }
        AssignmentTarget target = requireTarget(assignmentId, teachingClassId);
        if (target.getStatus() != AssignmentTarget.Status.PUBLISHED) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_PUBLISHED, "目标班级已撤回");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (target.windowState(now) != AssignmentTarget.WindowState.OPEN) {
            throw new ApiException(ErrorCode.TARGET_RULE_INVALID,
                    target.windowState(now) == AssignmentTarget.WindowState.NOT_STARTED
                            ? "作业尚未开始，暂不能收卷" : "作业已收卷");
        }
        Map<String, Object> before = Map.of("deadline", strOrEmpty(target.getDeadline()));
        target.updateRules(target.getPublishAt(), now,
                target.getMaxSubmissions(), target.getScoringRules());
        auditService.record(AuditActions.ASSIGNMENT_TARGET_COLLECTED, "ASSIGNMENT_TARGET",
                String.valueOf(target.getId()), before,
                Map.of("deadline", now.toString()));
        return target;
    }

    @Transactional
    public AssignmentTarget withdrawTarget(Long assignmentId, Long teachingClassId) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        if (user.isTeacher()) {
            requireCreator(assignment, user);
            classroomService.requirePrimaryAssignment(user.teacherId(), teachingClassId);
        }
        AssignmentTarget target = requireTarget(assignmentId, teachingClassId);
        if (target.getStatus() == AssignmentTarget.Status.WITHDRAWN) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_PUBLISHED, "目标班级已撤回");
        }
        Map<String, Object> before = Map.of("status", target.getStatus().name());
        target.withdraw();
        auditService.record(AuditActions.ASSIGNMENT_TARGET_WITHDRAWN, "ASSIGNMENT_TARGET",
                String.valueOf(target.getId()), before, Map.of("status", "WITHDRAWN"));
        if (targetRepository.findByAssignmentIdOrderByIdAsc(assignmentId).stream()
                .allMatch(t -> t.getStatus() == AssignmentTarget.Status.WITHDRAWN)) {
            assignment.withdraw();
        }
        return target;
    }

    @Transactional
    public AssignmentTarget republishTarget(Long assignmentId, Long teachingClassId) {
        CurrentUser user = accessGuard.requireAdminOrTeacher();
        Assignment assignment = requireAssignment(assignmentId);
        if (assignment.isExamLocked()) {
            examService.requireChangeAllowed(assignmentId, oj.exam.ExamService.ACTION_WITHDRAW);
        }
        if (user.isTeacher()) {
            requireCreator(assignment, user);
            classroomService.requirePrimaryAssignment(user.teacherId(), teachingClassId);
        }
        AssignmentTarget target = requireTarget(assignmentId, teachingClassId);
        if (target.getStatus() != AssignmentTarget.Status.WITHDRAWN) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_PUBLISHED, "目标班级未撤回");
        }
        target.republish();
        assignment.publish();
        auditService.record("ASSIGNMENT_TARGET_REPUBLISHED", "ASSIGNMENT_TARGET",
                String.valueOf(target.getId()), Map.of("status", "WITHDRAWN"),
                Map.of("status", "PUBLISHED"));
        return target;
    }

    // ---------------- 查询 ----------------

    public Assignment requireAssignment(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "作业不存在"));
    }

    public AssignmentTarget requireTargetById(Long targetId) {
        return targetRepository.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "目标班级不存在"));
    }

    public AssignmentTarget requireTarget(Long assignmentId, Long teachingClassId) {
        return targetRepository.findByAssignmentIdAndTeachingClassId(assignmentId, teachingClassId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "目标班级不存在"));
    }

    public List<Assignment> myAssignments(Long teacherId) {
        return assignmentRepository.findByCreatedByOrderByIdDesc(teacherId);
    }

    /**
     * 教师查看作业详情：创建者或目标班级授课教师可见。
     */
    public Assignment requireViewableAssignment(Long assignmentId, CurrentUser user) {
        Assignment assignment = requireAssignment(assignmentId);
        if (user.isAdmin()) {
            return assignment;
        }
        if (assignment.getCreatedBy().equals(user.teacherId())) {
            return assignment;
        }
        boolean assigned = targetRepository.findByAssignmentIdOrderByIdAsc(assignmentId).stream()
                .anyMatch(t -> {
                    try {
                        classroomService.requireActiveAssignment(user.teacherId(), t.getTeachingClassId());
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
        if (!assigned) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return assignment;
    }

    public List<AssignmentProblem> composition(Long assignmentId) {
        return assignmentProblemRepository.findByAssignmentIdOrderByOrderNumAsc(assignmentId);
    }

    public List<AssignmentTarget> targets(Long assignmentId) {
        return targetRepository.findByAssignmentIdOrderByIdAsc(assignmentId);
    }

    public List<ProblemSnapshot> snapshots(Long assignmentId) {
        return snapshotRepository.findByAssignmentIdOrderByProblemIdAsc(assignmentId);
    }

    private void requireCreator(Assignment assignment, CurrentUser user) {
        if (user.isAdmin()) {
            return;
        }
        if (!assignment.getCreatedBy().equals(user.teacherId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private static String strOrEmpty(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 学生视角：有效选课归属班级的已发布目标（含题目快照与公开样例）。
     */
    public List<AssignmentTarget> publishedTargetsForClass(Long teachingClassId) {
        return targetRepository.findByTeachingClassIdAndStatusOrderByIdAsc(
                teachingClassId, AssignmentTarget.Status.PUBLISHED);
    }

    /**
     * 对象级授权：学生必须通过有效选课归属访问目标班级。
     */
    public AssignmentTarget requireAccessibleTargetForStudent(Long studentId, Long targetId) {
        AssignmentTarget target = targetRepository.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "目标班级不存在"));
        classroomService.requireActiveEnrollment(studentId, target.getTeachingClassId());
        return target;
    }

    public TeachingClass requireTargetClass(AssignmentTarget target) {
        return classroomService.requireTeachingClass(target.getTeachingClassId());
    }
}
