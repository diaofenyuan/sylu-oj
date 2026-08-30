package oj.practice;

import oj.classroom.ClassroomService;
import oj.classroom.TeacherAssignment;
import oj.classroom.TestSupport;
import oj.shared.ApiException;
import oj.submission.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PracticeCatalogServiceTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private PracticeCatalogService practiceCatalogService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long studentId;

    @BeforeEach
    void setupScenario() {
        String[] tables = {"testcase_distribution", "judge_outbox", "judge_task", "judge_agent",
                "audit_event", "testcase_result", "judge_result", "submission", "submission_counter",
                "problem_snapshot", "assignment_target", "assignment_problem", "assignment", "testcase",
                "testcase_set", "problem", "problem_bank", "student_enrollment", "teacher_assignment",
                "teaching_class", "app_user", "student", "teacher", "course", "major", "term"};
        for (String table : tables) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        asAdmin();
        Long termId = classroomService.createTerm("PRACTICE", "刷题学期", LocalDate.now(), LocalDate.now().plusMonths(6)).getId();
        Long courseId = classroomService.createCourse("PRACTICE", "程序设计", BigDecimal.ONE).getId();
        Long classId = classroomService.createTeachingClass(termId, courseId, null, "PRACTICE", "刷题班").getId();
        Long teacherId = classroomService.createTeacher("PRACTICE-T", "刷题教师").getId();
        studentId = classroomService.createStudent("PRACTICE-S", "刷题学生").getId();
        classroomService.assignTeacher(classId, teacherId, TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);
        asStudent(studentId);
    }

    @Test
    void creates_one_hundred_problems_across_four_difficulties_for_student_class() {
        var problems = practiceCatalogService.listProblems(studentId, null);

        assertThat(problems).hasSize(100);
        assertThat(problems).extracting(PracticeCatalogService.PracticeProblem::difficulty)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.of("EASY", "BASIC", "INTERMEDIATE", "HARD")
                        .flatMap(level -> java.util.stream.Stream.generate(() -> level).limit(25)).toList());
        assertThat(problems).allMatch(problem -> problem.assignmentTargetId() != null);
    }

    @Test
    void rejects_student_without_active_enrollment() {
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> practiceCatalogService.listProblems(999999L, null));
    }

    @Test
    void difficulty_filter_returns_only_requested_level_and_public_samples() {
        var hard = practiceCatalogService.listProblems(studentId, "HARD");

        assertThat(hard).hasSize(25).allMatch(problem -> "HARD".equals(problem.difficulty()));
        var detail = practiceCatalogService.detail(studentId, hard.get(0).problemId());
        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).expectedOutput()).isEqualTo("4");
    }

    @Test
    void practice_submission_enters_existing_judge_sandbox_task_flow() {
        var problem = practiceCatalogService.listProblems(studentId, "EASY").get(0);

        var submission = submissionService.submit(new SubmissionService.SubmitCommand(
                problem.assignmentTargetId(), problem.problemId(), "CPP",
                "#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;}\n",
                "practice-sandbox-test"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_task WHERE submission_id = ?",
                Integer.class, submission.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_outbox WHERE event_type = 'JUDGE_TASK_CREATED'",
                Integer.class)).isEqualTo(1);
    }
}
