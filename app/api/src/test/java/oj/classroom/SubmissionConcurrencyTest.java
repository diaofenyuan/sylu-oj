package oj.classroom;

import oj.assignment.Assignment;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发提交原子计数：maxSubmissions 上限在并发下被严格守住，
 * 超限请求被原子拒绝（不产生重复计数）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionConcurrencyTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String[] CLEANUP = {
            "exam_spot_check", "exam_appeal", "exam_archive", "exam_approval", "exam_lock",
            "testcase_distribution", "judge_outbox", "judge_task", "judge_agent",
            "audit_event", "testcase_result", "judge_result", "submission", "submission_counter",
            "problem_snapshot", "assignment_target", "assignment_problem", "assignment",
            "testcase", "testcase_set", "problem", "problem_bank",
            "student_enrollment", "teacher_assignment", "teaching_class",
            "auth_token", "app_user", "grade_export_token", "grade_export",
            "teacher", "student", "course", "major", "term"
    };

    @BeforeEach
    void cleanDb() {
        for (String table : CLEANUP) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    private Long studentId;
    private Long problemId;
    private Long targetId;

    private void setupAssignment(int maxSubmissions) {
        asAdmin();
        Long termId = classroomService.createTerm("T", "学期", LocalDate.now(), LocalDate.now().plusMonths(6)).getId();
        Long courseId = classroomService.createCourse("C", "课程", BigDecimal.ONE).getId();
        Long classId = classroomService.createTeachingClass(termId, courseId, null, "A", "A班").getId();
        Long teacherId = classroomService.createTeacher("T1", "老师").getId();
        studentId = classroomService.createStudent("S1", "学生").getId();
        classroomService.assignTeacher(classId, teacherId, TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);

        asTeacher(teacherId);
        Long bankId = problemService.createBank(classId, "题库", null).getId();
        Problem problem = problemService.createProblem(bankId, "P1", "题1", "描述",
                languages("C", "CPP"), 1000, 128, 65536, new BigDecimal("100.00"),
                List.of(new ProblemService.TestcaseInput(1, true, "1 2", "3", BigDecimal.TEN)));
        problemService.publishProblem(problem.getId());
        Assignment assignment = assignmentService.createAssignment("并发", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(problem.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classId, new AssignmentService.TargetRule(
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), maxSubmissions, null)));
        AssignmentTarget target = assignmentService.targets(assignment.getId()).get(0);
        targetId = target.getId();
        problemId = problem.getId();
    }

    @Test
    void concurrent_submissions_never_exceed_max() throws Exception {
        setupAssignment(5);
        verifyConcurrentSubmissions(false, 5);
    }

    @Test
    void concurrent_replays_return_one_submission_even_at_limit() throws Exception {
        setupAssignment(1);
        verifyConcurrentSubmissions(true, 1);
    }

    private void verifyConcurrentSubmissions(boolean sameKey, int max) throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = sameKey ? "same-key" : "key-" + i;
            tasks.add(() -> {
                asStudent(studentId);
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试启动超时");
                    submissionService.submit(new SubmissionService.SubmitCommand(
                            targetId, problemId, "CPP", "int main(){return 0;}", key));
                    accepted.incrementAndGet();
                } catch (ApiException e) {
                    if (e.errorCode() == ErrorCode.SUBMISSION_LIMIT_EXCEEDED) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                } finally {
                    oj.auth.CurrentUserContext.clear();
                }
                return null;
            });
        }
        try {
            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(sameKey ? threads : max);
        assertThat(rejected.get()).isEqualTo(sameKey ? 0 : threads - max);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM submission_counter WHERE assignment_target_id = ? AND student_id = ?",
                Integer.class, targetId, studentId)).isEqualTo(max);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submission WHERE assignment_target_id = ? AND student_id = ?",
                Integer.class, targetId, studentId)).isEqualTo(max);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_task", Integer.class)).isEqualTo(max);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_outbox", Integer.class)).isEqualTo(max);
    }
}
