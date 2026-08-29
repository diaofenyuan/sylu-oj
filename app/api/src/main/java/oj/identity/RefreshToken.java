package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 刷新令牌：单次轮换、可撤销。仅存 SHA-256 哈希；familyId 串联轮换链，
 * 已轮换令牌被重用即判定令牌失窃，撤销整个 family。
 */
@Entity
@Table(name = "refresh_token", indexes = {
        @Index(name = "idx_refresh_token_user", columnList = "app_user_id"),
        @Index(name = "idx_refresh_token_family", columnList = "family_id")})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "app_user_id", nullable = false)
    private Long appUserId;

    @Column(name = "family_id", nullable = false, length = 64)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, Long appUserId, String familyId,
                        LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.appUserId = appUserId;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
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

    public String getFamilyId() {
        return familyId;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void consume(LocalDateTime now) {
        this.consumedAt = now;
    }

    public void revoke(LocalDateTime now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
