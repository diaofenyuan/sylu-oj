package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 管理员 TOTP：密钥以 AES-GCM 密文存储（密钥来自部署注入，不进仓库）。
 */
@Entity
@Table(name = "admin_totp")
public class AdminTotp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_user_id", nullable = false, unique = true)
    private Long appUserId;

    @Column(name = "secret_encrypted", nullable = false, length = 512)
    private String secretEncrypted;

    @Column(name = "confirmed", nullable = false, length = 8)
    private String confirmed = "N";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AdminTotp() {
    }

    public AdminTotp(Long appUserId, String secretEncrypted) {
        this.appUserId = appUserId;
        this.secretEncrypted = secretEncrypted;
        this.confirmed = "N";
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

    public Long getAppUserId() {
        return appUserId;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public boolean isConfirmed() {
        return "Y".equals(confirmed);
    }

    public void confirm() {
        this.confirmed = "Y";
    }
}
