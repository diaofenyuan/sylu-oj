package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 适配器运行状态（单行，id 固定为 1）：HALTED 时拒绝一切新登录。
 */
@Entity
@Table(name = "identity_adapter_status")
public class IdentityAdapterStatus {

    public static final long SINGLETON_ID = 1L;

    public enum Status {ACTIVE, HALTED}

    @Id
    @Column(name = "id")
    private Long id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "halted_reason", length = 64)
    private String haltedReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected IdentityAdapterStatus() {
    }

    public IdentityAdapterStatus(LocalDateTime now) {
        this.id = SINGLETON_ID;
        this.status = Status.ACTIVE;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public String getHaltedReason() {
        return haltedReason;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isHalted() {
        return status == Status.HALTED;
    }

    public void halt(String reason, LocalDateTime now) {
        this.status = Status.HALTED;
        this.haltedReason = reason;
        this.updatedAt = now;
    }

    public void resume(LocalDateTime now) {
        this.status = Status.ACTIVE;
        this.haltedReason = null;
        this.updatedAt = now;
    }
}
