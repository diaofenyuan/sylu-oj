package oj.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 审计事件：记录操作者、动作、目标与前后值。查询接口仅向管理员开放。
 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_event_created", columnList = "created_at"),
        @Index(name = "idx_audit_event_target", columnList = "target_type, target_id")
})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(name = "before_value", columnDefinition = "MEDIUMTEXT")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "MEDIUMTEXT")
    private String afterValue;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(String actorType, String actorId, String action,
                      String targetType, String targetId,
                      String beforeValue, String afterValue) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
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

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getTraceId() {
        return traceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
