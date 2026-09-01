package oj.auth;

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
 * 本地合成账号：仅 Windows 开发、联调与内测使用。
 * 生产认证由教务网页登录适配器承担（Task 5），该表在生产保持禁用。
 */
@Entity
@Table(name = "app_user", indexes = @Index(name = "idx_app_user_role", columnList = "role"))
public class AppUser {

    public enum Role {ADMIN, TEACHER, STUDENT}

    public enum Status {ACTIVE, DISABLED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_name", nullable = false, unique = true, length = 64)
    private String loginName;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "teacher_id")
    private Long teacherId;

    @Column(name = "student_id")
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AppUser() {
    }

    public AppUser(String loginName, String passwordHash, Role role, Long teacherId, Long studentId) {
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.teacherId = teacherId;
        this.studentId = studentId;
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

    public String getLoginName() {
        return loginName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public Status getStatus() {
        return status;
    }

    public void disable() {
        this.status = Status.DISABLED;
    }

    public void enable() {
        this.status = Status.ACTIVE;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
