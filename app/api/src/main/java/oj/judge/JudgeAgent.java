package oj.judge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 判题 Agent 注册表：身份、HMAC 签名密钥（AES-GCM 密文落库）与熔断状态。
 * 生产环境身份由 mTLS 客户端证书 CN 提供，密钥经部署注入；此处为运行态记录。
 */
@Entity
@Table(name = "judge_agent")
public class JudgeAgent {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "secret_encrypted", nullable = false, length = 256)
    private String secretEncrypted;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "suspension_reason", length = 256)
    private String suspensionReason;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    protected JudgeAgent() {
    }

    public JudgeAgent(String id, String displayName, String secretEncrypted) {
        this.id = id;
        this.displayName = displayName;
        this.secretEncrypted = secretEncrypted;
    }

    @PrePersist
    void onPrePersist() {
        if (registeredAt == null) {
            registeredAt = LocalDateTime.now();
        }
    }

    public void suspend(String reason, LocalDateTime now) {
        this.status = SUSPENDED;
        this.suspensionReason = reason;
        this.suspendedAt = now;
    }

    public void markSeen(LocalDateTime now) {
        this.lastSeenAt = now;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public String getStatus() {
        return status;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }
}
