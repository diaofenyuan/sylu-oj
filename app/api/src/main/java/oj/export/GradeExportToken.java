package oj.export;

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
 * 一次性下载授权：30 分钟有效，首次成功下载立即失效。
 */
@Entity
@Table(name = "grade_export_token", indexes = @Index(name = "idx_grade_export_token_export", columnList = "grade_export_id"))
public class GradeExportToken {

    public enum Status {ACTIVE, USED, EXPIRED, REVOKED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grade_export_id", nullable = false)
    private Long gradeExportId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_by", nullable = false)
    private Long issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    protected GradeExportToken() {
    }

    public GradeExportToken(Long gradeExportId, String tokenHash, Long issuedBy,
                            LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.gradeExportId = gradeExportId;
        this.tokenHash = tokenHash;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onPrePersist() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getGradeExportId() {
        return gradeExportId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getIssuedBy() {
        return issuedBy;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public Status getStatus() {
        return status;
    }

    public void markUsed() {
        this.status = Status.USED;
        this.usedAt = LocalDateTime.now();
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
    }

    public void revoke() {
        this.status = Status.REVOKED;
    }
}
