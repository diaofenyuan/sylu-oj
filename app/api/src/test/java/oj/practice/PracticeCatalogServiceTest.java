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
    private Long classId;
    private Long teacherId;

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
        classId = classroomService.createTeachingClass(termId, courseId, null, "PRACTICE", "刷题班").getId();
        teacherId = classroomService.createTeacher("PRACTICE-T", "刷题教师").getId();
        studentId = classroomService.createStudent("PRACTICE-S", "刷题学生").getId();
        classroomService.assignTeacher(classId, teacherId, TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);
        asStudent(studentId);
    }

    @Test
    void creates_three_hundred_problems_across_four_difficulties_for_student_class() {
        var problems = practiceCatalogService.listProblems(studentId, null);

        assertThat(problems).hasSize(300);
        assertThat(problems).extracting(PracticeCatalogService.PracticeProblem::title).doesNotHaveDuplicates();
        assertThat(problems).extracting(PracticeCatalogService.PracticeProblem::difficulty)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.of("EASY", "BASIC", "INTERMEDIATE", "HARD")
                        .flatMap(level -> java.util.stream.Stream.generate(() -> level).limit(75)).toList());
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

        assertThat(hard).hasSize(75).allMatch(problem -> "HARD".equals(problem.difficulty()));
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

    @Test
    void teacher_initializes_catalog_without_student_visit_and_repeated_access_is_idempotent() {
        asTeacher(teacherId);
        practiceCatalogService.ensureForTeachingClass(classId);
        practiceCatalogService.ensureForTeachingClass(classId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM problem", Integer.class)).isEqualTo(300);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignment_target", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM testcase_set", Integer.class)).isEqualTo(300);
        asStudent(studentId);
        assertThat(practiceCatalogService.listProblems(studentId, null)).hasSize(300);
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> practiceCatalogService.ensureForTeachingClass(classId));
        asTeacher(999999L);
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> practiceCatalogService.ensureForTeachingClass(classId));
    }

    @Test
    void extended_problems_have_hidden_cases_and_exactly_one_public_sample() {
        var problems = practiceCatalogService.listProblems(studentId, null);
        var extended = problems.stream().filter(p -> p.code().endsWith("-26")).toList();
        assertThat(extended).hasSize(4);
        for (var problem : extended) {
            var detail = practiceCatalogService.detail(studentId, problem.problemId());
            assertThat(detail.samples()).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM testcase tc JOIN testcase_set ts ON tc.testcase_set_id=ts.id
                    WHERE ts.problem_id=? AND tc.is_sample=FALSE
                    """, Integer.class, problem.problemId())).isGreaterThanOrEqualTo(4);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT SUM(tc.score) FROM testcase tc JOIN testcase_set ts ON tc.testcase_set_id=ts.id
                    WHERE ts.problem_id=?
                    """, BigDecimal.class, problem.problemId())).isEqualByComparingTo("100");
        }
        assertThat(problems).allMatch(p -> p.samples().isEmpty());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT ts.id FROM testcase_set ts JOIN testcase tc ON tc.testcase_set_id=ts.id
                  GROUP BY ts.id HAVING COUNT(*) >= 5 AND SUM(tc.score)=100
                ) verified
                """, Integer.class)).isEqualTo(200);
    }

    @Test
    void missing_primary_teacher_does_not_block_admin_from_opening_custom_banks() {
        asAdmin();
        jdbcTemplate.update("UPDATE teacher_assignment SET active_marker=NULL");
        practiceCatalogService.ensureForTeachingClass(classId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM problem", Integer.class)).isZero();
        asStudent(studentId);
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> practiceCatalogService.listProblems(studentId, null));
    }

    @Test
    void upgrades_v2_catalog_without_replacing_target_old_snapshots_or_submissions() {
        var original = practiceCatalogService.listProblems(studentId, null).get(0);
        var submission = submissionService.submit(new SubmissionService.SubmitCommand(
                original.assignmentTargetId(), original.problemId(), "PYTHON", "print(sum(map(int,input().split())))", "before-upgrade"));
        Long snapshotId = jdbcTemplate.queryForObject("SELECT id FROM problem_snapshot WHERE problem_id=?", Long.class, original.problemId());
        jdbcTemplate.update("""
                DELETE FROM problem_snapshot WHERE problem_id IN
                (SELECT id FROM problem WHERE CAST(SUBSTRING(code, LENGTH(code)-1, 2) AS INTEGER)>25)
                """);
        jdbcTemplate.update("""
                DELETE FROM assignment_problem WHERE problem_id IN
                (SELECT id FROM problem WHERE CAST(SUBSTRING(code, LENGTH(code)-1, 2) AS INTEGER)>25)
                """);
        jdbcTemplate.update("UPDATE assignment_target SET scoring_rules=?", "{\"catalog\":\"SYLU_OJ_PRACTICE_V2\",\"policy\":\"bestScore\"}");
        var upgraded = practiceCatalogService.listProblems(studentId, null);
        assertThat(upgraded).hasSize(300).allMatch(p -> p.assignmentTargetId().equals(original.assignmentTargetId()));
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM problem_snapshot WHERE problem_id=?", Long.class, original.problemId())).isEqualTo(snapshotId);
        assertThat(jdbcTemplate.queryForObject("SELECT assignment_target_id FROM submission WHERE id=?", Long.class, submission.getId())).isEqualTo(original.assignmentTargetId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignment", Integer.class)).isEqualTo(1);
        assertThat(practiceCatalogService.listProblems(studentId, " hard ")).hasSize(75);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM problem_snapshot", Integer.class)).isEqualTo(300);
    }
}
