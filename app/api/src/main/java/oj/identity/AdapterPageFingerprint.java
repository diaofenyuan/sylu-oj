package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 教务登录页结构指纹：记录页面关键字段哈希；变化即熔断适配器并告警。
 */
@Entity
@Table(name = "adapter_page_fingerprint")
public class AdapterPageFingerprint {

    public enum Status {OK, CHANGED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_url", nullable = false)
    private String pageUrl;

    @Column(name = "fingerprint_hash", nullable = false, length = 64)
    private String fingerprintHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    protected AdapterPageFingerprint() {
    }

    public AdapterPageFingerprint(String pageUrl, String fingerprintHash, Status status,
                                  LocalDateTime capturedAt) {
        this.pageUrl = pageUrl;
        this.fingerprintHash = fingerprintHash;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getFingerprintHash() {
        return fingerprintHash;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }
}
