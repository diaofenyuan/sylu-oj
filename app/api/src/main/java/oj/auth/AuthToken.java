package oj.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 会话令牌（开发/内测用）：仅存储 SHA-256 哈希，明文令牌只在签发响应中出现一次。
 */
@Entity
@Table(name = "auth_token")
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "app_user_id", nullable = false)
    private Long appUserId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected AuthToken() {
    }

    public AuthToken(String tokenHash, Long appUserId, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.appUserId = appUserId;
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

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }
}
