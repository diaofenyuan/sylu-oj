package oj.identity;

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
 * 外部身份：以学号/工号为唯一外部键绑定本地账号。
 * 学号复用/身份变更通过断言指纹变化识别，进入人工确认而不是静默继承。
 */
@Entity
@Table(name = "external_identity",
        indexes = @Index(name = "idx_external_identity_status", columnList = "status"))
public class ExternalIdentity {

    public enum Type {STUDENT, STAFF}

    public enum Status {ACTIVE, PENDING_CONFIRMATION, DISABLED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_no", nullable = false, unique = true, length = 32)
    private String externalNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_type", nullable = false, length = 16)
    private Type externalType;

    @Column(name = "app_user_id", nullable = false, unique = true)
    private Long appUserId;

    @Column(name = "assertion_fingerprint", nullable = false, length = 64)
    private String assertionFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.ACTIVE;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "bound_at", nullable = false)
    private LocalDateTime boundAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ExternalIdentity() {
    }

    public ExternalIdentity(String externalNo, Type externalType, Long appUserId,
                            String assertionFingerprint, LocalDateTime now) {
        this.externalNo = externalNo;
        this.externalType = externalType;
        this.appUserId = appUserId;
        this.assertionFingerprint = assertionFingerprint;
        this.boundAt = now;
        this.createdAt = now;
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

    public String getExternalNo() {
        return externalNo;
    }

    public Type getExternalType() {
        return externalType;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public String getAssertionFingerprint() {
        return assertionFingerprint;
    }

    public Status getStatus() {
        return status;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public LocalDateTime getBoundAt() {
        return boundAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordLoginSuccess(LocalDateTime now) {
        this.lastLoginAt = now;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    /** 失败累计；同一身份 5 次失败锁定 15 分钟（账号维度限流）。 */
    public void recordLoginFailure(LocalDateTime now) {
        this.failedAttempts++;
        if (this.failedAttempts >= 5) {
            this.lockedUntil = now.plusMinutes(15);
        }
    }

    public void markPendingConfirmation(String newFingerprint) {
        this.status = Status.PENDING_CONFIRMATION;
        this.assertionFingerprint = newFingerprint;
    }

    public void confirmActive(LocalDateTime now) {
        this.status = Status.ACTIVE;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public void disable() {
        this.status = Status.DISABLED;
    }
}
