package oj.judge;

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
 * 判题 Outbox：与提交/任务同一事务写入，由调度器向 RabbitMQ（TLS）投递。
 * (event_type, task_uuid) 唯一保证重复事件幂等。
 */
@Entity
@Table(name = "judge_outbox", indexes = @Index(name = "idx_judge_outbox_pending", columnList = "status, id"))
public class JudgeOutbox {

    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";

    public static final String EVENT_TASK_CREATED = "JUDGE_TASK_CREATED";
    public static final String EVENT_TASK_RETRY = "JUDGE_TASK_RETRY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "task_uuid", nullable = false, length = 36)
    private String taskUuid;

    @Column(name = "payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    protected JudgeOutbox() {
    }

    public JudgeOutbox(String eventType, String taskUuid, String payload) {
        this.eventType = eventType;
        this.taskUuid = taskUuid;
        this.payload = payload;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void markPublished(LocalDateTime now) {
        this.status = PUBLISHED;
        this.publishedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTaskUuid() {
        return taskUuid;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
