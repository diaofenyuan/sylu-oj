package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次性登录 state：哈希唯一、短有效期、消费后不可再用，并绑定来源 IP 与精确回调地址。
 */
@Entity
@Table(name = "login_state")
public class LoginState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "callback_url", nullable = false)
    private String callbackUrl;

    @Column(name = "source_ip", nullable = false, length = 64)
    private String sourceIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    protected LoginState() {
    }

    public LoginState(String stateHash, String callbackUrl, String sourceIp,
                      LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.stateHash = stateHash;
        this.callbackUrl = callbackUrl;
        this.sourceIp = sourceIp;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getStateHash() {
        return stateHash;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
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
