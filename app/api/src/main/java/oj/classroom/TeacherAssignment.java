package oj.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 教师授课关系：PRIMARY 主讲 / ASSISTANT 助教。
 * 有效行 active_marker=1，结束行 active_marker=NULL，配合唯一约束
 * (teaching_class_id, teacher_id, active_marker) 保证每班每教师至多一条有效关系。
 */
@Entity
@Table(name = "teacher_assignment", indexes = {
        @Index(name = "idx_teacher_assignment_teacher", columnList = "teacher_id"),
        @Index(name = "idx_teacher_assignment_class", columnList = "teaching_class_id")
})
public class TeacherAssignment {

    public enum Role {PRIMARY, ASSISTANT}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teaching_class_id", nullable = false)
    private Long teachingClassId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    /**
     * 1 = 有效；NULL = 已结束。切勿直接读取，使用 isActive()。
     */
    @Column(name = "active_marker")
    private Long activeMarker;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TeacherAssignment() {
    }

    public TeacherAssignment(Long teachingClassId, Long teacherId, Role role) {
        this.teachingClassId = teachingClassId;
        this.teacherId = teacherId;
        this.role = role;
        this.validFrom = LocalDateTime.now();
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

    public Long getTeachingClassId() {
        return teachingClassId;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public Role getRole() {
        return role;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public LocalDateTime getValidTo() {
        return validTo;
    }

    public boolean isActive() {
        return activeMarker != null;
    }

    public void end() {
        this.activeMarker = null;
        this.validTo = LocalDateTime.now();
    }
}
