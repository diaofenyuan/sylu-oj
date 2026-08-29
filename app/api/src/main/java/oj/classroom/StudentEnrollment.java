package oj.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 学生选课归属：同一学生同一学期至多一条有效归属。
 * 唯一约束 (student_id, term_id, active_marker) 在数据库层强制该不变量；
 * 转班必须原子地结束旧归属并建立新归属（同一事务 + 审计）。
 */
@Entity
@Table(name = "student_enrollment", indexes = {
        @Index(name = "idx_enrollment_student", columnList = "student_id"),
        @Index(name = "idx_enrollment_class", columnList = "teaching_class_id")
})
public class StudentEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "teaching_class_id", nullable = false)
    private Long teachingClassId;

    /** 冗余学期列，仅用于数据库唯一约束，业务读取以教学班为准。 */
    @Column(name = "term_id", nullable = false)
    private Long termId;

    @Column(name = "enrolled_at", nullable = false)
    private LocalDateTime enrolledAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /**
     * 1 = 有效；NULL = 已结束。
     */
    @Column(name = "active_marker")
    private Long activeMarker;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StudentEnrollment() {
    }

    public StudentEnrollment(Long studentId, Long teachingClassId, Long termId) {
        this.studentId = studentId;
        this.teachingClassId = teachingClassId;
        this.termId = termId;
        this.enrolledAt = LocalDateTime.now();
        this.activeMarker = 1L;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public Long getTeachingClassId() {
        return teachingClassId;
    }

    public Long getTermId() {
        return termId;
    }

    public LocalDateTime getEnrolledAt() {
        return enrolledAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public boolean isActive() {
        return activeMarker != null;
    }

    public void end() {
        this.activeMarker = null;
        this.endedAt = LocalDateTime.now();
    }
}
