package oj.classroom;

import oj.audit.AuditService;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教学组织服务：系统管理员手工维护学期、专业、课程、教学班、
 * 教师授课关系与学生选课归属。所有变更记录前后值与审计事件。
 */
@Service
public class ClassroomService {

    private final TermRepository termRepository;
    private final MajorRepository majorRepository;
    private final CourseRepository courseRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TeachingClassRepository classRepository;
    private final TeacherAssignmentRepository teacherAssignmentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AuditService auditService;
    private final AccessGuard accessGuard;

    public ClassroomService(TermRepository termRepository, MajorRepository majorRepository,
                             CourseRepository courseRepository, TeacherRepository teacherRepository,
                             StudentRepository studentRepository, TeachingClassRepository classRepository,
                             TeacherAssignmentRepository teacherAssignmentRepository,
                             StudentEnrollmentRepository enrollmentRepository,
                             AuditService auditService, AccessGuard accessGuard) {
        this.termRepository = termRepository;
        this.majorRepository = majorRepository;
        this.courseRepository = courseRepository;
        this.teacherRepository = teacherRepository;
        this.studentRepository = studentRepository;
        this.classRepository = classRepository;
        this.teacherAssignmentRepository = teacherAssignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
    }

    // ---------------- 学期 ----------------

    @Transactional
    public Term createTerm(String code, String name, LocalDate startDate, LocalDate endDate) {
        accessGuard.requireAdmin();
        if (termRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        if (startDate != null && endDate != null && !endDate.isAfter(startDate)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "学期结束日期必须晚于开始日期");
        }
        Term term = termRepository.save(new Term(code, name, startDate, endDate));
        auditService.record(AuditActions.TERM_CREATED, "TERM", String.valueOf(term.getId()),
                null, Map.of("code", code, "name", name));
        return term;
    }

    @Transactional
    public Term closeTerm(Long termId) {
        accessGuard.requireAdmin();
        Term term = termRepository.findById(termId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Map<String, Object> before = Map.of("status", term.getStatus().name());
        term.close();
        auditService.record("TERM_CLOSED", "TERM", String.valueOf(termId), before,
                Map.of("status", "CLOSED"));
        return term;
    }

    public List<Term> listTerms() {
        return termRepository.findByOrderByStartDateDesc();
    }

    // ---------------- 专业 / 课程 ----------------

    @Transactional
    public Major createMajor(String code, String name) {
        accessGuard.requireAdmin();
        if (majorRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        Major major = majorRepository.save(new Major(code, name));
        auditService.record(AuditActions.MAJOR_CREATED, "MAJOR", String.valueOf(major.getId()),
                null, Map.of("code", code, "name", name));
        return major;
    }

    public List<Major> listMajors() {
        return majorRepository.findByOrderByNameAsc();
    }

    @Transactional
    public Course createCourse(String code, String name, BigDecimal credit) {
        accessGuard.requireAdmin();
        if (courseRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        Course course = courseRepository.save(new Course(code, name, credit));
        auditService.record(AuditActions.COURSE_CREATED, "COURSE", String.valueOf(course.getId()),
                null, Map.of("code", code, "name", name));
        return course;
    }

    public List<Course> listCourses() {
        return courseRepository.findByOrderByNameAsc();
    }

    // ---------------- 教师 / 学生 ----------------

    @Transactional
    public Teacher createTeacher(String staffNo, String name) {
        accessGuard.requireAdmin();
        if (teacherRepository.existsByStaffNo(staffNo)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        Teacher teacher = teacherRepository.save(new Teacher(staffNo, name));
        auditService.record(AuditActions.TEACHER_CREATED, "TEACHER", String.valueOf(teacher.getId()),
                null, Map.of("staffNo", staffNo, "name", name));
        return teacher;
    }

    public List<Teacher> listTeachers() {
        return teacherRepository.findByStatusOrderByNameAsc(Teacher.Status.ACTIVE);
    }

    @Transactional
    public Student createStudent(String studentNo, String name) {
        accessGuard.requireAdmin();
        if (studentRepository.existsByStudentNo(studentNo)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        Student student = studentRepository.save(new Student(studentNo, name));
        auditService.record(AuditActions.STUDENT_CREATED, "STUDENT", String.valueOf(student.getId()),
                null, Map.of("studentNo", studentNo, "name", name));
        return student;
    }

    public List<Student> listStudents(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return studentRepository.findByStatusOrderByNameAsc(Student.Status.ACTIVE);
        }
        String part = keyword.trim();
        return studentRepository.findByStudentNoContainingOrNameContainingOrderById(part, part);
    }

    // ---------------- 教学班 ----------------

    @Transactional
    public TeachingClass createTeachingClass(Long termId, Long courseId, Long majorId, String code, String name) {
        accessGuard.requireAdmin();
        Term term = termRepository.findById(termId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "学期不存在"));
        if (term.getStatus() == Term.Status.CLOSED) {
            throw new ApiException(ErrorCode.TERM_CLOSED);
        }
        if (!courseRepository.existsById(courseId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (majorId != null && !majorRepository.existsById(majorId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "专业不存在");
        }
        if (classRepository.existsByTermIdAndCourseIdAndCode(termId, courseId, code)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        TeachingClass teachingClass = classRepository.save(new TeachingClass(termId, courseId, majorId, code, name));
        auditService.record(AuditActions.CLASS_CREATED, "TEACHING_CLASS", String.valueOf(teachingClass.getId()),
                null, Map.of("termId", termId, "courseId", courseId, "code", code, "name", name));
        return teachingClass;
    }

    public List<TeachingClass> listTeachingClasses(Long termId, Long courseId) {
        if (termId != null && courseId != null) {
            return classRepository.findByTermIdAndCourseIdOrderByIdAsc(termId, courseId);
        }
        if (termId != null) {
            return classRepository.findByTermIdOrderByIdAsc(termId);
        }
        return classRepository.findAllByOrderByIdAsc();
    }

    // ---------------- 教师授课关系 ----------------

    @Transactional
    public TeacherAssignment assignTeacher(Long teachingClassId, Long teacherId, TeacherAssignment.Role role) {
        accessGuard.requireAdmin();
        requireTeachingClass(teachingClassId);
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "教师不存在"));
        if (teacher.getStatus() != Teacher.Status.ACTIVE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "教师已停用");
        }
        if (teacherAssignmentRepository.existsByTeachingClassIdAndTeacherIdAndActiveMarkerIsNotNull(teachingClassId, teacherId)) {
            throw new ApiException(ErrorCode.TEACHER_ALREADY_ASSIGNED);
        }
        TeacherAssignment assignment = teacherAssignmentRepository
                .save(new TeacherAssignment(teachingClassId, teacherId, role));
        auditService.record(AuditActions.TEACHER_ASSIGNED, "TEACHER_ASSIGNMENT",
                String.valueOf(assignment.getId()),
                null,
                Map.of("teachingClassId", teachingClassId, "teacherId", teacherId, "role", role.name()));
        return assignment;
    }

    @Transactional
    public void removeTeacherAssignment(Long assignmentId) {
        accessGuard.requireAdmin();
        TeacherAssignment assignment = teacherAssignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!assignment.isActive()) {
            throw new ApiException(ErrorCode.ENROLLMENT_NOT_ACTIVE, "授课关系已结束");
        }
        if (assignment.getRole() == TeacherAssignment.Role.PRIMARY
                && teacherAssignmentRepository.countByTeachingClassIdAndRoleAndActiveMarkerIsNotNull(
                assignment.getTeachingClassId(), TeacherAssignment.Role.PRIMARY) <= 1) {
            throw new ApiException(ErrorCode.CLASS_REQUIRES_PRIMARY);
        }
        Map<String, Object> before = Map.of(
                "teachingClassId", assignment.getTeachingClassId(),
                "teacherId", assignment.getTeacherId(),
                "role", assignment.getRole().name(),
                "active", true);
        assignment.end();
        auditService.record(AuditActions.TEACHER_ASSIGNMENT_ENDED, "TEACHER_ASSIGNMENT",
                String.valueOf(assignmentId), before,
                Map.of("active", false, "validTo", assignment.getValidTo().toString()));
    }

    public List<TeacherAssignment> listTeacherAssignments(Long teachingClassId) {
        accessGuard.requireAdminOrTeacher();
        return teacherAssignmentRepository.findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(teachingClassId);
    }

    /**
     * 教师查询自己当前有效授课关系覆盖的教学班。
     */
    public List<TeacherAssignment> myTeachingAssignments(Long teacherId) {
        return teacherAssignmentRepository.findByTeacherIdAndActiveMarkerIsNotNullOrderByIdDesc(teacherId);
    }

    /**
     * 对象级授权：教师必须在该教学班拥有当前有效授课关系。
     */
    public TeacherAssignment requireActiveAssignment(Long teacherId, Long teachingClassId) {
        return teacherAssignmentRepository
                .findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(teachingClassId).stream()
                .filter(a -> a.getTeacherId().equals(teacherId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    /**
     * 对象级授权：仅主讲教师（或管理员场景由调用方处理）可执行发布/撤回/修订类操作。
     */
    public TeacherAssignment requirePrimaryAssignment(Long teacherId, Long teachingClassId) {
        TeacherAssignment assignment = requireActiveAssignment(teacherId, teachingClassId);
        if (assignment.getRole() != TeacherAssignment.Role.PRIMARY) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅主讲教师可执行该操作");
        }
        return assignment;
    }

    // ---------------- 学生选课归属 ----------------

    @Transactional
    public StudentEnrollment enrollStudent(Long teachingClassId, Long studentId, boolean transfer) {
        accessGuard.requireAdmin();
        TeachingClass teachingClass = requireTeachingClass(teachingClassId);
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "学生不存在"));
        if (student.getStatus() != Student.Status.ACTIVE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "学生已停用");
        }
        // 行锁防止并发转班/重复分班产生多条有效归属
        StudentEnrollment existing = enrollmentRepository.findActiveByStudentIdForUpdate(studentId).orElse(null);
        if (existing != null) {
            if (existing.getTeachingClassId().equals(teachingClassId)) {
                throw new ApiException(ErrorCode.STUDENT_ALREADY_ENROLLED);
            }
            if (!existing.getTermId().equals(teachingClass.getTermId())) {
                throw new ApiException(ErrorCode.STUDENT_ALREADY_ENROLLED,
                        "学生在其他学期存在有效归属，与本次操作学期不同，请先结束旧归属");
            }
            if (!transfer) {
                throw new ApiException(ErrorCode.STUDENT_ALREADY_ENROLLED);
            }
            return transferEnrollment(existing, teachingClass);
        }
        StudentEnrollment enrollment = enrollmentRepository
                .save(new StudentEnrollment(studentId, teachingClassId, teachingClass.getTermId()));
        auditService.record(AuditActions.STUDENT_ENROLLED, "STUDENT_ENROLLMENT",
                String.valueOf(enrollment.getId()),
                null,
                Map.of("studentId", studentId, "teachingClassId", teachingClassId,
                        "termId", teachingClass.getTermId()));
        return enrollment;
    }

    private StudentEnrollment transferEnrollment(StudentEnrollment existing, TeachingClass newClass) {
        Map<String, Object> before = Map.of(
                "studentId", existing.getStudentId(),
                "fromTeachingClassId", existing.getTeachingClassId(),
                "active", true);
        existing.end();
        // 先刷新“结束”操作，避免新有效行插入时与旧有效行在唯一约束上冲突
        enrollmentRepository.saveAndFlush(existing);
        StudentEnrollment created = enrollmentRepository.saveAndFlush(new StudentEnrollment(
                existing.getStudentId(), newClass.getId(), newClass.getTermId()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("studentId", existing.getStudentId());
        after.put("fromTeachingClassId", existing.getTeachingClassId());
        after.put("toTeachingClassId", newClass.getId());
        after.put("active", true);
        auditService.record(AuditActions.STUDENT_TRANSFERRED, "STUDENT_ENROLLMENT",
                String.valueOf(created.getId()), before, after);
        return created;
    }

    @Transactional
    public void endEnrollment(Long enrollmentId) {
        accessGuard.requireAdmin();
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!enrollment.isActive()) {
            throw new ApiException(ErrorCode.ENROLLMENT_NOT_ACTIVE);
        }
        Map<String, Object> before = Map.of(
                "studentId", enrollment.getStudentId(),
                "teachingClassId", enrollment.getTeachingClassId(),
                "active", true);
        enrollment.end();
        auditService.record(AuditActions.ENROLLMENT_ENDED, "STUDENT_ENROLLMENT",
                String.valueOf(enrollmentId), before, Map.of("active", false));
    }

    public List<StudentEnrollment> listEnrollments(Long teachingClassId) {
        return enrollmentRepository.findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(teachingClassId);
    }

    /**
     * 对象级授权：学生必须在该教学班拥有当前有效归属。
     */
    public StudentEnrollment requireActiveEnrollment(Long studentId, Long teachingClassId) {
        return enrollmentRepository
                .findByStudentIdAndTeachingClassIdAndActiveMarkerIsNotNull(studentId, teachingClassId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    /**
     * 学生当前有效归属（任意班级）。
     */
    public StudentEnrollment requireActiveEnrollmentAny(Long studentId) {
        return enrollmentRepository.findByStudentIdAndActiveMarkerIsNotNull(studentId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "学生当前没有有效教学班归属"));
    }

    public TeachingClass requireTeachingClass(Long teachingClassId) {
        return classRepository.findById(teachingClassId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "教学班不存在"));
    }

    public Term requireTerm(Long termId) {
        return termRepository.findById(termId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "学期不存在"));
    }
}
