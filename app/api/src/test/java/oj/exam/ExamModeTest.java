package oj.exam;

import oj.assignment.Assignment;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.classroom.ClassroomService;
import oj.classroom.TestSupport;
import oj.problem.Problem;
import oj.problem.ProblemService;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import oj.submission.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 9 验收：EXAM 发布即自动锁定（运行时冻结 + 策略校验和）、
 * 锁定后修改闸门（双人审批 + TOTP 二次认证）、不可变归档、
 * 申诉复判差异不自动采用、≥5% 抽查。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamModeTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private oj.submission.JudgeResultService judgeResultService;
    @Autowired
    private ExamService examService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String[] CLEANUP = {
            "exam_spot_check", "exam_appeal", "exam_archive", "exam_approval", "exam_lock",
            "testcase_distribution", "judge_outbox", "judge_task", "judge_agent",
            "audit_event", "testcase_result", "judge_result", "submission", "submission_counter",
            "problem_snapshot", "assignment_target", "assignment_problem", "assignment",
            "testcase", "testcase_set", "problem", "problem_bank",
            "student_enrollment", "teacher_assignment", "teaching_class",
            "totp_enrollment", "admin_totp", "identity_adapter_status", "adapter_page_fingerprint",
            "consumed_ticket", "refresh_token", "login_state", "external_identity",
            "auth_token", "app_user", "grade_export_token", "grade_export",
            "teacher", "student", "course", "major", "term"
    };

    private Long assignmentId;
    private Long problemId;
    private Long teacherId;
    private Long studentId;
    private Long approverUserId;      // 第二审批人的 app_user（TOTP 已确认）

    @BeforeEach
    void setup() {
        for (String table : CLEANUP) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        asAdmin();
        Long termId = classroomService.createTerm("T9", "学期", LocalDate.now(), LocalDate.now().plusMonths(6)).getId();
        Long courseId = classroomService.createCourse("C9", "课程", BigDecimal.ONE).getId();
        Long classId = classroomService.createTeachingClass(termId, courseId, null, "A9", "A班").getId();
        teacherId = classroomService.createTeacher("T9T", "老师").getId();
        studentId = classroomService.createStudent("T9S", "学生").getId();
        classroomService.assignTeacher(classId, teacherId, oj.classroom.TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);

        asTeacher(teacherId);
        Long bankId = problemService.createBank(classId, "题库T9", null).getId();
        Problem problem = problemService.createProblem(bankId, "P9", "题9", "描述",
                languages("CPP"), 1000, 128, 65536, new BigDecimal("100.00"),
                List.of(new ProblemService.TestcaseInput(1, true, "1 2", "3", new BigDecimal("100.00"))));
        problemService.publishProblem(problem.getId());
        problemId = problem.getId();
        Assignment assignment = assignmentService.createAssignment("考试T9", Assignment.Mode.EXAM,
                List.of(new AssignmentService.CompositionItem(problemId, new BigDecimal("100.00"))));
        assignmentId = assignment.getId();
        assignmentService.publish(assignmentId,
                Map.of(classId, new AssignmentService.TargetRule(
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), 100, null)));

        // 第二审批人（即测试中的 ADMIN 上下文，appUserId=1）：TOTP 已确认
        approverUserId = 1L;
        jdbcTemplate.update("INSERT INTO app_user (id, login_name, password_hash, role, status, created_at) " +
                "VALUES (1, 'approver', 'x', 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO admin_totp (app_user_id, secret_encrypted, confirmed, created_at) " +
                "VALUES (1, 'x', 'Y', CURRENT_TIMESTAMP)");
    }

    private Long submit(String key) {
        asStudent(studentId);
        return submissionService.submit(new SubmissionService.SubmitCommand(
                assignmentService.targets(assignmentId).get(0).getId(), problemId, "CPP",
                "int main(){return 0;}", key)).getId();
    }

    @Test
    void exam_publish_auto_locks_and_freezes_runtime() {
        // 发布即锁定
        assertThat(examService.isLocked(assignmentId)).isTrue();
        Map<String, Object> lock = jdbcTemplate.queryForMap(
                "SELECT runtime_ids, policy_checksum FROM exam_lock WHERE assignment_id = ?", assignmentId);
        assertThat((String) lock.get("runtime_ids")).contains("gcc-13.3-c++20", "cpython-3.12");
        assertThat((String) lock.get("policy_checksum")).hasSize(64);
        Integer lockedAudit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'EXAM_LOCKED'", Integer.class);
        assertThat(lockedAudit).isEqualTo(1);
    }

    @Test
    void locked_exam_changes_require_dual_approval_with_totp() {
        AssignmentTarget target = assignmentService.targets(assignmentId).get(0);

        // 未批准：修改被拒（EXAM_LOCKED）
        asTeacher(teacherId);
        assertThatThrownBy(() -> assignmentService.updateTargetRules(assignmentId,
                target.getTeachingClassId(), new AssignmentService.TargetRule(
                        LocalDateTime.now(), LocalDateTime.now().plusDays(2), 10, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EXAM_LOCKED);

        // 第一人发起审批后自批被拒
        ExamApproval approval = examService.requestChange(assignmentId,
                ExamService.ACTION_CHANGE_TARGET_RULES, "延长考试窗口");
        assertThatThrownBy(() -> examService.decide(approval.getId(), true, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EXAM_SELF_APPROVAL);

        // 第二审批人无 TOTP 被拒（二次认证）
        jdbcTemplate.update("UPDATE admin_totp SET confirmed = 'N' WHERE app_user_id = ?", approverUserId);
        asAdmin();
        assertThatThrownBy(() -> examService.decide(approval.getId(), true, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.MFA_ENROLLMENT_REQUIRED);
        jdbcTemplate.update("UPDATE admin_totp SET confirmed = 'Y' WHERE app_user_id = ?", approverUserId);

        // TOTP 已确认的第二人批准 → 同动作放行
        asAdmin();
        examService.decide(approval.getId(), true, "同意延长");
        asTeacher(teacherId);
        assignmentService.updateTargetRules(assignmentId, target.getTeachingClassId(),
                new AssignmentService.TargetRule(
                        LocalDateTime.now(), LocalDateTime.now().plusDays(2), 10, null));
        Integer allowed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'EXAM_CHANGE_ALLOWED'", Integer.class);
        assertThat(allowed).isGreaterThanOrEqualTo(1);
    }

    @Test
    void archive_is_immutable_with_checksum() {
        submit("k-exam-1");
        submit("k-exam-2");

        asTeacher(teacherId);
        ExamArchive archive = examService.exportArchive(assignmentId);
        assertThat(archive.getChecksum()).hasSize(64);
        assertThat(archive.getSubmissionCount()).isEqualTo(2);
        // 重复归档被拒：同一考试仅一份归档记录
        assertThatThrownBy(() -> examService.exportArchive(assignmentId))
                .isInstanceOf(Exception.class);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_archive WHERE assignment_id = ?", Integer.class, assignmentId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void appeal_creates_rejudge_and_records_diff_without_auto_adoption() {
        Long submissionId = submit("k-exam-3");
        // 先注入原始判题结果（申诉对象）
        judgeResultService.record(new oj.submission.JudgeResultService.ResultCommand(
                submissionId, "AC", new BigDecimal("100.00"), 12, 300, 1, "agent-1",
                List.of(new oj.submission.JudgeResultService.TestcaseOutcome(
                        1, "AC", new BigDecimal("100.00"), 5, 100))));

        asTeacher(teacherId);
        ExamAppeal appeal = examService.createAppeal(assignmentId, submissionId, "学生申诉");
        assertThat(appeal.getStatus()).isEqualTo(ExamAppeal.PENDING);

        // 复判任务已排队（judge_task 新 attempt）
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM judge_task WHERE submission_id = ?", Integer.class, submissionId);
        assertThat(tasks).isEqualTo(2);

        // 复判结果落库回调：差异写入申诉单，REJUDGED 等待人工复核
        examService.applyRejudgeOutcome(submissionId, "WA", new BigDecimal("0.00"));
        ExamAppeal updated = examService.appeals(assignmentId).get(0);
        assertThat(updated.getStatus()).isEqualTo(ExamAppeal.REJUDGED);
        assertThat(updated.getDiffNote()).contains("复判差异");
    }

    @Test
    void spot_check_samples_at_least_five_percent() {
        for (int i = 0; i < 20; i++) {
            submit("k-spot-" + i);
        }
        asTeacher(teacherId);
        List<ExamSpotCheck> sampled = examService.spotCheck(assignmentId);
        assertThat(sampled.size()).isGreaterThanOrEqualTo((int) Math.ceil(20 * 0.05));
        Integer seedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT seed) FROM exam_spot_check WHERE assignment_id = ?", Integer.class, assignmentId);
        assertThat(seedRows).isEqualTo(1);
    }
}
