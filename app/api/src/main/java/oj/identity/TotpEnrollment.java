package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * TOTP 绑定的一次性令牌：管理员经教务登录触发 MFA 绑定时签发，短有效期、单次消费。
 */
@Entity
@Table(name = "totp_enrollment")
public class TotpEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "app_user_id", nullable = false)
    private Long appUserId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    protected TotpEnrollment() {
    }

    public TotpEnrollment(String tokenHash, Long appUserId, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.appUserId = appUserId;
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

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(LocalDateTime now) {
        this.consumedAt = now;
    }
}
