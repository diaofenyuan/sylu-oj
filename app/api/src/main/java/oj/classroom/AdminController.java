package oj.classroom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import oj.auth.AppUser;
import oj.auth.AppUserRepository;
import oj.auth.LocalAccountService;
import oj.audit.AuditEvent;
import oj.audit.AuditEventRepository;
import oj.audit.AuditService;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 系统管理员工作流：手工维护学期、专业、课程、教学班、教师、学生、
 * 授课关系与选课归属；创建本地合成账号（仅开发/内测）；查询审计事件。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ClassroomService classroomService;
    private final LocalAccountService localAccountService;
    private final AuditEventRepository auditEventRepository;
    private final AccessGuard accessGuard;
    private final AppUserRepository appUserRepository;
    private final AuditService auditService;

    public AdminController(ClassroomService classroomService,
                           LocalAccountService localAccountService,
                           AuditEventRepository auditEventRepository,
                           AccessGuard accessGuard,
                           AppUserRepository appUserRepository,
                           AuditService auditService) {
        this.classroomService = classroomService;
        this.localAccountService = localAccountService;
        this.auditEventRepository = auditEventRepository;
        this.accessGuard = accessGuard;
        this.appUserRepository = appUserRepository;
        this.auditService = auditService;
    }

    // ---------------- 请求体 ----------------

    public record TermRequest(@NotBlank String code, @NotBlank String name,
                              @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    }

    public record MajorRequest(@NotBlank String code, @NotBlank String name) {
    }

    public record CourseRequest(@NotBlank String code, @NotBlank String name, BigDecimal credit) {
    }

    public record ClassRequest(@NotNull Long termId, @NotNull Long courseId, Long majorId,
                               @NotBlank String code, @NotBlank String name) {
    }

    public record TeacherRequest(@NotBlank String staffNo, @NotBlank String name) {
    }

    public record StudentRequest(@NotBlank String studentNo, @NotBlank String name) {
    }

    public record AssignTeacherRequest(@NotNull Long teachingClassId, @NotNull Long teacherId,
                                       @NotNull TeacherAssignment.Role role) {
    }

    public record EnrollRequest(@NotNull Long teachingClassId, @NotNull Long studentId,
                                boolean transfer) {
    }

    public record DevAccountRequest(@NotNull AppUser.Role role, @NotBlank String loginName,
                                    @NotBlank String password, Long teacherId, Long studentId) {
    }

    // ---------------- 学期 / 专业 / 课程 ----------------

    @PostMapping("/terms")
    public Term createTerm(@Valid @RequestBody TermRequest request) {
        return classroomService.createTerm(request.code(), request.name(),
                request.startDate(), request.endDate());
    }

    @PostMapping("/terms/{id}/close")
    public Map<String, Object> closeTerm(@PathVariable Long id) {
        classroomService.closeTerm(id);
        return Map.of("ok", true);
    }

    @GetMapping("/terms")
    public List<Term> terms() {
        accessGuard.requireAdmin();
        return classroomService.listTerms();
    }

    @PostMapping("/majors")
    public Major createMajor(@Valid @RequestBody MajorRequest request) {
        return classroomService.createMajor(request.code(), request.name());
    }

    @GetMapping("/majors")
    public List<Major> majors() {
        accessGuard.requireAdmin();
        return classroomService.listMajors();
    }

    @PostMapping("/courses")
    public Course createCourse(@Valid @RequestBody CourseRequest request) {
        return classroomService.createCourse(request.code(), request.name(),
                request.credit() == null ? BigDecimal.ONE : request.credit());
    }

    @GetMapping("/courses")
    public List<Course> courses() {
        accessGuard.requireAdmin();
        return classroomService.listCourses();
    }

    // ---------------- 教学班 ----------------

    @PostMapping("/teaching-classes")
    public TeachingClass createClass(@Valid @RequestBody ClassRequest request) {
        return classroomService.createTeachingClass(request.termId(), request.courseId(),
                request.majorId(), request.code(), request.name());
    }

    @GetMapping("/teaching-classes")
    public List<TeachingClass> classes(@RequestParam(required = false) Long termId,
                                       @RequestParam(required = false) Long courseId) {
        accessGuard.requireAdmin();
        return classroomService.listTeachingClasses(termId, courseId);
    }

    // ---------------- 教师 / 学生 ----------------

    @PostMapping("/teachers")
    public Teacher createTeacher(@Valid @RequestBody TeacherRequest request) {
        return classroomService.createTeacher(request.staffNo(), request.name());
    }

    @GetMapping("/teachers")
    public List<Teacher> teachers() {
        accessGuard.requireAdmin();
        return classroomService.listTeachers();
    }

    @PostMapping("/students")
    public Student createStudent(@Valid @RequestBody StudentRequest request) {
        return classroomService.createStudent(request.studentNo(), request.name());
    }

    @GetMapping("/students")
    public List<Student> students(@RequestParam(required = false) String keyword) {
        accessGuard.requireAdmin();
        return classroomService.listStudents(keyword);
    }

    // ---------------- 授课关系与选课归属 ----------------

    @PostMapping("/teacher-assignments")
    public TeacherAssignment assignTeacher(@Valid @RequestBody AssignTeacherRequest request) {
        return classroomService.assignTeacher(request.teachingClassId(), request.teacherId(), request.role());
    }

    @DeleteMapping("/teacher-assignments/{id}")
    public Map<String, Object> removeTeacherAssignment(@PathVariable Long id) {
        classroomService.removeTeacherAssignment(id);
        return Map.of("ok", true);
    }

    @GetMapping("/teacher-assignments")
    public List<TeacherAssignment> teacherAssignments(@RequestParam Long teachingClassId) {
        accessGuard.requireAdmin();
        return classroomService.listTeacherAssignments(teachingClassId);
    }

    @PostMapping("/enrollments")
    public StudentEnrollment enroll(@Valid @RequestBody EnrollRequest request) {
        return classroomService.enrollStudent(request.teachingClassId(), request.studentId(), request.transfer());
    }

    @DeleteMapping("/enrollments/{id}")
    public Map<String, Object> endEnrollment(@PathVariable Long id) {
        classroomService.endEnrollment(id);
        return Map.of("ok", true);
    }

    @GetMapping("/enrollments")
    public List<StudentEnrollment> enrollments(@RequestParam Long teachingClassId) {
        accessGuard.requireAdmin();
        return classroomService.listEnrollments(teachingClassId);
    }

    // ---------------- 本地合成账号（仅开发/内测） ----------------

    /** 账号列表展示（不含密码哈希），供管理员维护多个账号。 */
    public record AccountView(Long id, String loginName, AppUser.Role role,
                              Long teacherId, Long studentId, AppUser.Status status, String createdAt) {
    }

    @GetMapping("/accounts")
    public List<AccountView> accounts() {
        accessGuard.requireAdmin();
        return appUserRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .map(u -> new AccountView(u.getId(), u.getLoginName(), u.getRole(),
                        u.getTeacherId(), u.getStudentId(), u.getStatus(),
                        u.getCreatedAt() == null ? null : u.getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/dev-accounts")
    public Map<String, Object> createDevAccount(@Valid @RequestBody DevAccountRequest request) {
        accessGuard.requireAdmin();
        AppUser user = localAccountService.createLocalAccount(request.role(), request.loginName(),
                request.password(), request.teacherId(), request.studentId());
        return Map.of("id", user.getId(), "loginName", user.getLoginName(), "role", user.getRole().name());
    }

    @PostMapping("/accounts/{id}/disable")
    public Map<String, Object> disableAccount(@PathVariable Long id) {
        accessGuard.requireAdmin();
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        user.disable();
        appUserRepository.save(user);
        auditService.record(AuditActions.ACCOUNT_DISABLED, "APP_USER", String.valueOf(id), null, null);
        return Map.of("ok", true);
    }

    // ---------------- 审计查询 ----------------

    @GetMapping("/audit-events")
    public List<AuditEvent> auditEvents(@RequestParam(required = false) String targetType,
                                        @RequestParam(required = false) String targetId,
                                        @RequestParam(defaultValue = "50") int limit) {
        accessGuard.requireAdmin();
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        if (targetType != null && targetId != null) {
            return auditEventRepository.latestForTarget(targetType, targetId, pageable).getContent();
        }
        return auditEventRepository.findAll(pageable).getContent();
    }
}
