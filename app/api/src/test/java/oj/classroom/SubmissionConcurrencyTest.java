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

    @Test
    void concurrent_submissions_never_exceed_max() throws Exception {
        asAdmin();
        Long termId = classroomService.createTerm("T", "学期", LocalDate.now(), LocalDate.now().plusMonths(6)).getId();
        Long courseId = classroomService.createCourse("C", "课程", BigDecimal.ONE).getId();
        Long classId = classroomService.createTeachingClass(termId, courseId, null, "A", "A班").getId();
        Long teacherId = classroomService.createTeacher("T1", "老师").getId();
        Long studentId = classroomService.createStudent("S1", "学生").getId();
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
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), 5, null)));
        AssignmentTarget target = assignmentService.targets(assignment.getId()).get(0);

        int threads = 8;
        int max = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "key-" + i;
            tasks.add(() -> {
                asStudent(studentId);
                try {
                    submissionService.submit(new SubmissionService.SubmitCommand(
                            target.getId(), problem.getId(), "CPP", "int main(){return 0;}", key));
                    accepted.incrementAndGet();
                } catch (ApiException e) {
                    if (e.errorCode() == ErrorCode.SUBMISSION_LIMIT_EXCEEDED) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // 传播非预期异常
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(max);
        assertThat(rejected.get()).isEqualTo(threads - max);
    }
}
