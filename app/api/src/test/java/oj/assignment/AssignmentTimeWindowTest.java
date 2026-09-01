package oj.assignment;

import oj.classroom.ClassroomService;
import oj.classroom.TestSupport;
import oj.classroom.TeacherAssignment;
import oj.problem.Problem;
import oj.problem.ProblemService;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import oj.submission.Submission;
import oj.submission.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间窗口能力：可留空（NULL=不限）、定时发布（未开始拦截）、
 * 立即收卷（一键截止）与发布后修改时间。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssignmentTimeWindowTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private SubmissionService submissionService;

    private Long classId;
    private Long teacherId;
    private Long studentId;
    private Long problemId;

    @BeforeEach
    void setupOrg() {
        asAdmin();
        Long termId = classroomService.createTerm("T2026T", "2026秋", LocalDate.now().minusDays(10),
                LocalDate.now().plusMonths(5)).getId();
        Long courseId = classroomService.createCourse("CS202", "数据结构", BigDecimal.ONE).getId();
        classId = classroomService.createTeachingClass(termId, courseId, null, "X", "教学班X").getId();
        teacherId = classroomService.createTeacher("T101", "窗口教师").getId();
        studentId = classroomService.createStudent("S101", "窗口学生").getId();
        classroomService.assignTeacher(classId, teacherId, TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);
        Long bankId = problemService.createBank(classId, "窗口题库", null).getId();
        asTeacher(teacherId);
        Problem problem = problemService.createProblem(bankId, "W1", "窗口题", "题面",
                List.of("CPP"), 1000, 128, 65536, new BigDecimal("100.00"),
                List.of(new ProblemService.TestcaseInput(1, true, "1", "1", BigDecimal.TEN)));
        problemService.publishProblem(problem.getId());
        problemId = problem.getId();
    }

    private Assignment publish(Map<Long, AssignmentService.TargetRule> targets) {
        asTeacher(teacherId);
        Assignment assignment = assignmentService.createAssignment("窗口作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(problemId, new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(), targets);
        return assignment;
    }

    private AssignmentTarget targetOf(Assignment assignment, Long classId) {
        return assignmentService.targets(assignment.getId()).stream()
                .filter(t -> t.getTeachingClassId().equals(classId))
                .findFirst().orElseThrow();
    }

    private Submission submit(Long targetId, String key) {
        asStudent(studentId);
        return submissionService.submit(new SubmissionService.SubmitCommand(
                targetId, problemId, "CPP", "int main(){return 0;}", key));
    }

    private LocalDateTime future() {
        return LocalDateTime.now().plusDays(7);
    }

    @Test
    void nullable_window_opens_immediately_without_deadline() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(null, null, 10, null)));
        AssignmentTarget target = targetOf(assignment, classId);
        assertThat(target.getPublishAt()).isNull();
        assertThat(target.getDeadline()).isNull();
        assertThat(target.windowState(LocalDateTime.now()))
                .isEqualTo(AssignmentTarget.WindowState.OPEN);
        assertThat(submit(target.getId(), "k1").getAttemptNo()).isEqualTo(1);
    }

    @Test
    void publish_at_null_and_deadline_set_closes_after_deadline() {
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(null, pastDeadline, 10, null)));
        AssignmentTarget target = targetOf(assignment, classId);
        assertThat(target.windowState(LocalDateTime.now()))
                .isEqualTo(AssignmentTarget.WindowState.CLOSED);
        assertThatThrownBy(() -> submit(target.getId(), "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.WINDOW_CLOSED);
    }

    @Test
    void scheduled_publish_blocks_before_start() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(LocalDateTime.now().plusDays(2), future(), 10, null)));
        AssignmentTarget target = targetOf(assignment, classId);
        assertThat(target.windowState(LocalDateTime.now()))
                .isEqualTo(AssignmentTarget.WindowState.NOT_STARTED);
        assertThatThrownBy(() -> submit(target.getId(), "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ASSIGNMENT_NOT_STARTED);
    }

    @Test
    void collect_closes_window_immediately_and_requires_open() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(LocalDateTime.now().minusDays(1), future(), 10, null)));
        AssignmentTarget target = targetOf(assignment, classId);
        assertThat(submit(target.getId(), "k1").getAttemptNo()).isEqualTo(1);
        asTeacher(teacherId);
        assignmentService.collectTarget(assignment.getId(), classId);
        AssignmentTarget after = targetOf(assignment, classId);
        assertThat(after.windowState(LocalDateTime.now()))
                .isEqualTo(AssignmentTarget.WindowState.CLOSED);
        asStudent(studentId);
        assertThatThrownBy(() -> submit(target.getId(), "k2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.WINDOW_CLOSED);
        // 重复收卷：已收卷，拒绝
        asTeacher(teacherId);
        assertThatThrownBy(() -> assignmentService.collectTarget(assignment.getId(), classId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TARGET_RULE_INVALID);
    }

    @Test
    void collect_on_not_started_is_rejected() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(LocalDateTime.now().plusDays(2), future(), 10, null)));
        asTeacher(teacherId);
        assertThatThrownBy(() -> assignmentService.collectTarget(assignment.getId(), classId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TARGET_RULE_INVALID);
    }

    @Test
    void rules_can_be_updated_after_publish_and_must_keep_valid_window() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(LocalDateTime.now().minusDays(1), future(), 10, null)));
        asTeacher(teacherId);
        LocalDateTime extend = future().plusDays(3);
        assignmentService.updateTargetRules(assignment.getId(), classId,
                new AssignmentService.TargetRule(LocalDateTime.now().minusDays(1), extend, 10, null));
        assertThat(targetOf(assignment, classId).getDeadline()).isEqualTo(extend);
        assertThatThrownBy(() -> assignmentService.updateTargetRules(assignment.getId(), classId,
                new AssignmentService.TargetRule(LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(1),
                        10, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TARGET_RULE_INVALID);
    }

    @Test
    void target_detail_view_includes_window_state() {
        Assignment assignment = publish(Map.of(classId,
                new AssignmentService.TargetRule(LocalDateTime.now().minusDays(1), future(), 10, null)));
        asTeacher(teacherId);
        var targets = assignmentService.targets(assignment.getId());
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).isOpenAt(LocalDateTime.now())).isTrue();
    }
}
