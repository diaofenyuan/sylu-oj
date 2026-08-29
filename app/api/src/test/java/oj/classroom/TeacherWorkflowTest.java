package oj.classroom;

import oj.assignment.Assignment;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.analytics.AnalyticsService;
import oj.export.ExportFileWriter;
import oj.export.GradeExport;
import oj.export.GradeExportService;
import oj.export.GradeExportRepository;
import oj.problem.Problem;
import oj.problem.ProblemService;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import oj.submission.JudgeResultService;
import oj.submission.Submission;
import oj.submission.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 4 核心业务验证：教学组织、题库、组卷发布、提交计数、分析排名与成绩导出。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TeacherWorkflowTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private JudgeResultService judgeResultService;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private GradeExportService exportService;
    @Autowired
    private GradeExportRepository exportRepository;
    @Autowired
    private oj.classroom.TermRepository termRepository;
    @Autowired
    private oj.classroom.CourseRepository courseRepository;
    @Autowired
    private oj.classroom.TeacherRepository teacherRepository;
    @Autowired
    private oj.classroom.StudentRepository studentRepository;
    @Autowired
    private oj.submission.SubmissionCounterRepository counterRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // 基础组织：两个同课程同学期教学班 A、B
    private Long termId;
    private Long courseId;
    private Long classA;
    private Long classB;
    // 教师：t1 主讲A；t2 助教A+主讲B；t3 仅助教B
    private Long t1;
    private Long t2;
    private Long t3;
    // 学生：s1、s2 在 A；s3 在 B
    private Long s1;
    private Long s2;
    private Long s3;

    private int seq = 0;

    @BeforeEach
    void setupOrg() {
        asAdmin();
        termId = classroomService.createTerm("T2026S", "2026春", LocalDate.now().minusDays(10),
                LocalDate.now().plusMonths(5)).getId();
        courseId = classroomService.createCourse("CS101", "程序设计基础", BigDecimal.ONE).getId();
        classA = classroomService.createTeachingClass(termId, courseId, null, "A", "教学班A").getId();
        classB = classroomService.createTeachingClass(termId, courseId, null, "B", "教学班B").getId();
        t1 = classroomService.createTeacher("T001", "主讲A").getId();
        t2 = classroomService.createTeacher("T002", "教师2").getId();
        t3 = classroomService.createTeacher("T003", "教师3").getId();
        classroomService.assignTeacher(classA, t1, TeacherAssignment.Role.PRIMARY);
        classroomService.assignTeacher(classA, t2, TeacherAssignment.Role.ASSISTANT);
        classroomService.assignTeacher(classB, t2, TeacherAssignment.Role.PRIMARY);
        classroomService.assignTeacher(classB, t3, TeacherAssignment.Role.ASSISTANT);
        s1 = classroomService.createStudent("S001", "张三").getId();
        s2 = classroomService.createStudent("S002", "李四").getId();
        s3 = classroomService.createStudent("S003", "王五").getId();
        classroomService.enrollStudent(classA, s1, false);
        classroomService.enrollStudent(classA, s2, false);
        classroomService.enrollStudent(classB, s3, false);
    }

    // ---------------- 辅助 ----------------

    private Problem newProblem(Long bankId, String code, String title) {
        return newProblem(bankId, code, title, t1);
    }

    private Problem newProblem(Long bankId, String code, String title, Long teacherId) {
        asTeacher(teacherId);
        return problemService.createProblem(bankId, code, title, "题目描述",
                languages("C", "CPP"), 1000, 128, 65536, new BigDecimal("100.00"),
                List.of(new ProblemService.TestcaseInput(1, true, "1 2", "3", BigDecimal.TEN),
                        new ProblemService.TestcaseInput(2, false, "10 20", "30", BigDecimal.TEN)));
    }

    private Assignment publishAssignment(Assignment.Mode mode, List<AssignmentService.CompositionItem> items,
                                         Map<Long, AssignmentService.TargetRule> targets) {
        asTeacher(t1);
        Assignment assignment = assignmentService.createAssignment("作业-" + (++seq), mode, items);
        assignmentService.publish(assignment.getId(), targets);
        return assignment;
    }

    private AssignmentTarget targetFor(Long assignmentId, Long classId) {
        return assignmentService.targets(assignmentId).stream()
                .filter(t -> t.getTeachingClassId().equals(classId))
                .findFirst().orElseThrow();
    }

    private LocalDateTime past() {
        return LocalDateTime.now().minusHours(1);
    }

    private LocalDateTime future() {
        return LocalDateTime.now().plusDays(7);
    }

    private Submission submit(Long studentId, Long targetId, Long problemId, String key) {
        asStudent(studentId);
        return submissionService.submit(new SubmissionService.SubmitCommand(
                targetId, problemId, "CPP", "int main(){return 0;}", key));
    }

    private void judge(Long submissionId, String code, String score) {
        judgeResultService.record(new JudgeResultService.ResultCommand(
                submissionId, code, new BigDecimal(score), 100, 1024, 1, "agent-1", null));
    }

    // ---------------- 教学组织 ----------------

    @Test
    void admin_maintains_multiple_classes_per_course_term() {
        var classes = classroomService.listTeachingClasses(termId, courseId);
        assertThat(classes).hasSize(2);
        assertThat(classes).extracting(TeachingClass::getCode).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void last_primary_cannot_be_removed() {
        asAdmin();
        TeacherAssignment t1Assignment = classroomService.listTeacherAssignments(classA).stream()
                .filter(a -> a.getTeacherId().equals(t1))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> classroomService.removeTeacherAssignment(t1Assignment.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLASS_REQUIRES_PRIMARY);
    }

    @Test
    void student_transfer_is_atomic_and_audited() {
        asAdmin();
        // 重复分班到同班拒绝
        assertThatThrownBy(() -> classroomService.enrollStudent(classA, s1, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.STUDENT_ALREADY_ENROLLED);
        // 未显式 transfer 拒绝跨班
        assertThatThrownBy(() -> classroomService.enrollStudent(classB, s1, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.STUDENT_ALREADY_ENROLLED);
        // transfer=true 原子转班
        classroomService.enrollStudent(classB, s1, true);
        assertThat(classroomService.listEnrollments(classA)).extracting(StudentEnrollment::getStudentId)
                .doesNotContain(s1);
        assertThat(classroomService.listEnrollments(classB)).extracting(StudentEnrollment::getStudentId)
                .contains(s1);
    }

    // ---------------- 组卷与发布 ----------------

    @Test
    void weight_sum_must_be_exactly_100() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        assertThatThrownBy(() -> assignmentService.createAssignment("作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("60.00")))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.WEIGHT_NOT_100);
    }

    @Test
    void assistant_cannot_publish() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        asTeacher(t2);
        Assignment assignment = assignmentService.createAssignment("作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        // t2 在 A 是助教，发布被拒
        assertThatThrownBy(() -> assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 5, null))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void primary_publishes_to_multiple_classes_with_independent_rules() {
        // 让 t1 同时主讲 A、B，才能对两班发布
        asAdmin();
        classroomService.assignTeacher(classB, t1, TeacherAssignment.Role.PRIMARY);
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("多班作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        asTeacher(t1);
        assignmentService.publish(assignment.getId(), Map.of(
                classA, new AssignmentService.TargetRule(past(), future(), 5, null),
                classB, new AssignmentService.TargetRule(past(), future(), 10, null)));
        assertThat(targetFor(assignment.getId(), classA).getMaxSubmissions()).isEqualTo(5);
        assertThat(targetFor(assignment.getId(), classB).getMaxSubmissions()).isEqualTo(10);
        // 修改 A 规则不影响 B
        assignmentService.updateTargetRules(assignment.getId(), classA,
                new AssignmentService.TargetRule(past(), future(), 7, null));
        assertThat(targetFor(assignment.getId(), classA).getMaxSubmissions()).isEqualTo(7);
        assertThat(targetFor(assignment.getId(), classB).getMaxSubmissions()).isEqualTo(10);
    }

    @Test
    void exam_locked_after_publish() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("考试", Assignment.Mode.EXAM,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 3, null)));
        // 发布后锁定：修改规则 / 重开 / 补发均被拒
        assertThatThrownBy(() -> assignmentService.updateTargetRules(assignment.getId(), classA,
                new AssignmentService.TargetRule(past(), future(), 9, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EXAM_LOCKED);
        assertThatThrownBy(() -> assignmentService.publish(assignment.getId(),
                Map.of(classB, new AssignmentService.TargetRule(past(), future(), 9, null))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EXAM_LOCKED);
    }

    @Test
    void cross_class_batch_publish_is_atomically_rejected() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("越权发布", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        // t1 只授课 A，却对 B 发布 → 整个事务回滚，A 也不产生 target
        asTeacher(t1);
        assertThatThrownBy(() -> assignmentService.publish(assignment.getId(), Map.of(
                classA, new AssignmentService.TargetRule(past(), future(), 5, null),
                classB, new AssignmentService.TargetRule(past(), future(), 5, null))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(assignmentService.targets(assignment.getId())).isEmpty();
        assertThat(assignmentService.requireAssignment(assignment.getId()).getStatus())
                .isEqualTo(Assignment.Status.DRAFT);
    }

    @Test
    void publish_freezes_problem_snapshot() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("快照作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 5, null)));
        int snapshotVersion = assignmentService.snapshots(assignment.getId()).get(0).getProblemVersion();
        // 发布后修改题库题目（递增版本）
        problemService.updateProblem(p1.getId(), "新标题", null, null, 0, 0, 0, null);
        // 作业快照保持发布时的版本与题面
        var snapshot = assignmentService.snapshots(assignment.getId()).get(0);
        assertThat(snapshot.getProblemVersion()).isEqualTo(snapshotVersion);
        assertThat(snapshot.getTitle()).isEqualTo("题1");
    }

    // ---------------- 提交与计数 ----------------

    private Assignment setupAssignableHomework(int maxSubmissions) {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("提交作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), maxSubmissions, null)));
        return assignment;
    }

    @Test
    void repeated_submission_within_limit_and_idempotency_replay() {
        Assignment assignment = setupAssignableHomework(5);
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Long problemId = assignmentService.composition(assignment.getId()).get(0).getProblemId();
        // 窗口内不同幂等键重复提交
        Submission first = submit(s1, target.getId(), problemId, "key-1");
        Submission second = submit(s1, target.getId(), problemId, "key-2");
        assertThat(second.getAttemptNo()).isEqualTo(2);
        // 同幂等键重放：返回原提交，不消耗次数
        Submission replay = submit(s1, target.getId(), problemId, "key-1");
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(counterRepository.lockCounter(target.getId(), s1).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void submission_limit_exceeded_is_rejected() {
        Assignment assignment = setupAssignableHomework(2);
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Long problemId = assignmentService.composition(assignment.getId()).get(0).getProblemId();
        submit(s1, target.getId(), problemId, "k1");
        submit(s1, target.getId(), problemId, "k2");
        assertThatThrownBy(() -> submit(s1, target.getId(), problemId, "k3"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.SUBMISSION_LIMIT_EXCEEDED);
    }

    @Test
    void closed_window_rejects_submission() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("已结束", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(
                        LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), 5, null)));
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Long problemId = assignmentService.composition(assignment.getId()).get(0).getProblemId();
        assertThatThrownBy(() -> submit(s1, target.getId(), problemId, "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.WINDOW_CLOSED);
    }

    // ---------------- 分析与排名 ----------------

    @Test
    void competition_ranking_with_ties() {
        Assignment assignment = setupAssignableHomework(10);
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Long problemId = assignmentService.composition(assignment.getId()).get(0).getProblemId();
        Submission a = submit(s1, target.getId(), problemId, "a");
        Submission b = submit(s2, target.getId(), problemId, "b");
        judge(a.getId(), "AC", "90.00");
        judge(b.getId(), "AC", "80.00");
        // 第三名：s3 在 B 班，不属于 A 目标，不影响 A 排名
        asTeacher(t1);
        var analytics = analyticsService.targetAnalyticsForTeacher(target.getId());
        // 当前只有 s1、s2 两个学生（都在 A 班），分数 90 / 80
        assertThat(analytics.rows()).hasSize(2);
        var rowS1 = analytics.rows().stream().filter(r -> r.studentId().equals(s1)).findFirst().orElseThrow();
        var rowS2 = analytics.rows().stream().filter(r -> r.studentId().equals(s2)).findFirst().orElseThrow();
        assertThat(rowS1.rank()).isEqualTo(1);
        assertThat(rowS2.rank()).isEqualTo(2);
        assertThat(rowS1.totalScore()).isEqualByComparingTo("90.00");
    }

    @Test
    void ranking_uses_competition_numbering_with_shared_rank() {
        Assignment assignment = setupAssignableHomework(10);
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Long problemId = assignmentService.composition(assignment.getId()).get(0).getProblemId();
        Submission a = submit(s1, target.getId(), problemId, "a");
        Submission b = submit(s2, target.getId(), problemId, "b");
        judge(a.getId(), "AC", "80.00");
        judge(b.getId(), "AC", "80.00");
        asTeacher(t1);
        var analytics = analyticsService.targetAnalyticsForTeacher(target.getId());
        assertThat(analytics.rows()).allMatch(r -> r.rank() == 1);
    }

    @Test
    void analytics_computes_totals_and_pass_rate() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("分析作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 10, null)));
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Submission a = submit(s1, target.getId(), p1.getId(), "a");
        Submission b = submit(s2, target.getId(), p1.getId(), "b");
        judge(a.getId(), "AC", "100.00");
        judge(b.getId(), "WA", "50.00");
        asTeacher(t1);
        var analytics = analyticsService.targetAnalyticsForTeacher(target.getId());
        var rowS1 = analytics.rows().stream().filter(r -> r.studentId().equals(s1)).findFirst().orElseThrow();
        assertThat(rowS1.totalScore()).isEqualByComparingTo("100.00");
        assertThat(rowS1.passRate()).isEqualByComparingTo("100.00");
        assertThat(rowS1.submissionCount()).isEqualTo(1);
        var rowS2 = analytics.rows().stream().filter(r -> r.studentId().equals(s2)).findFirst().orElseThrow();
        assertThat(rowS2.totalScore()).isEqualByComparingTo("50.00");
        assertThat(rowS2.passRate()).isEqualByComparingTo("0.00");
    }

    // ---------------- 对象级授权 ----------------

    @Test
    void teacher_cannot_access_unassigned_class() {
        // t1 仅授课 A，尝试访问 B 班题库被拒
        asTeacher(t1);
        assertThatThrownBy(() -> problemService.listBanks(classB))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void student_cannot_see_other_class_assignments() {
        // s1 在 A，尝试访问 B 班目标 → 无归属，被拒
        Long bankB = problemService.createBank(classB, "题库B", null).getId();
        Problem pB = newProblem(bankB, "PB", "B班题", t2);
        asTeacher(t2);
        problemService.publishProblem(pB.getId());
        Assignment assignment = assignmentService.createAssignment("B班作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(pB.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classB, new AssignmentService.TargetRule(past(), future(), 5, null)));
        AssignmentTarget targetB = targetFor(assignment.getId(), classB);
        asStudent(s1);
        assertThatThrownBy(() -> assignmentService.requireAccessibleTargetForStudent(s1, targetB.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------- 成绩导出 ----------------

    private Assignment setupExportableAssignment() {
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("导出作业", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 10, null)));
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        Submission a = submit(s1, target.getId(), p1.getId(), "e1");
        judge(a.getId(), "AC", "100.00");
        return assignment;
    }

    @Test
    void export_contains_no_source_code_and_is_desensitized() throws Exception {
        Assignment assignment = setupExportableAssignment();
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        asTeacher(t1);
        GradeExport export = exportService.create(new GradeExportService.CreateCommand(
                target.getId(), GradeExport.Format.CSV, null, null));
        exportService.generate(export.getId());
        GradeExport loaded = exportRepository.findById(export.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(GradeExport.Status.READY);
        assertThat(loaded.getMatchCount()).isEqualTo(2); // s1、s2 都在 A 班
        // 下载内容不含源代码
        String token = (String) exportService.issueDownloadToken(export.getId()).get("token");
        byte[] content = exportService.download(token).content();
        String csvText = unzipSingle(content, "submission-details.csv");
        assertThat(csvText).doesNotContain("int main");
        assertThat(csvText).contains("张三");
    }

    @Test
    void export_token_is_single_use() throws Exception {
        Assignment assignment = setupExportableAssignment();
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        asTeacher(t1);
        GradeExport export = exportService.create(new GradeExportService.CreateCommand(
                target.getId(), GradeExport.Format.XLSX, null, null));
        exportService.generate(export.getId());
        String token = (String) exportService.issueDownloadToken(export.getId()).get("token");
        exportService.download(token); // 第一次成功
        assertThatThrownBy(() -> exportService.download(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EXPORT_TOKEN_USED);
    }

    @Test
    void export_formula_injection_is_neutralized() throws Exception {
        // 学生姓名以 "=" 开头，导出后应被前置单引号
        asAdmin();
        Long evil = classroomService.createStudent("S777", "=HYPERLINK()").getId();
        classroomService.enrollStudent(classA, evil, false);
        Long bankA = problemService.createBank(classA, "题库A", null).getId();
        Problem p1 = newProblem(bankA, "P1", "题1");
        asTeacher(t1);
        problemService.publishProblem(p1.getId());
        Assignment assignment = assignmentService.createAssignment("注入测试", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(p1.getId(), new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classA, new AssignmentService.TargetRule(past(), future(), 10, null)));
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        asTeacher(t1);
        GradeExport export = exportService.create(new GradeExportService.CreateCommand(
                target.getId(), GradeExport.Format.CSV, null, null));
        exportService.generate(export.getId());
        String token = (String) exportService.issueDownloadToken(export.getId()).get("token");
        String csv = unzipSingle(exportService.download(token).content(), "summary.csv");
        assertThat(csv).contains("'=HYPERLINK()");
    }

    @Test
    void export_filter_narrows_but_never_expands() {
        Assignment assignment = setupExportableAssignment();
        AssignmentTarget target = targetFor(assignment.getId(), classA);
        asTeacher(t1);
        GradeExport export = exportService.create(new GradeExportService.CreateCommand(
                target.getId(), GradeExport.Format.CSV, "S001", null));
        exportService.generate(export.getId());
        GradeExport loaded = exportRepository.findById(export.getId()).orElseThrow();
        assertThat(loaded.getMatchCount()).isEqualTo(1);
    }

    private String unzipSingle(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("ZIP 中缺少 " + entryName);
    }
}
