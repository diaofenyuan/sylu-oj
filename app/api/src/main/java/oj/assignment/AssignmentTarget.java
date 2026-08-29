package oj.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 作业目标班级：每个目标独立记录发布时间窗口、最大提交次数与计分规则；
 * 修改一个目标班级的规则不得影响其他目标班级。
 */
@Entity
@Table(name = "assignment_target", indexes = {
        @Index(name = "idx_assignment_target_assignment", columnList = "assignment_id"),
        @Index(name = "idx_assignment_target_class", columnList = "teaching_class_id")
})
public class AssignmentTarget {

    public enum Status {PUBLISHED, WITHDRAWN}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "teaching_class_id", nullable = false)
    private Long teachingClassId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PUBLISHED;

    @Column(name = "publish_at", nullable = false)
    private LocalDateTime publishAt;

    @Column(name = "deadline", nullable = false)
    private LocalDateTime deadline;

    @Column(name = "max_submissions", nullable = false)
    private int maxSubmissions;

    /** 计分规则 JSON（如 bestScorePolicy/rounding），目标班级间独立。 */
    @Column(name = "scoring_rules", length = 512)
    private String scoringRules;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AssignmentTarget() {
    }

    public AssignmentTarget(Long assignmentId, Long teachingClassId,
                            LocalDateTime publishAt, LocalDateTime deadline,
                            int maxSubmissions, String scoringRules) {
        this.assignmentId = assignmentId;
        this.teachingClassId = teachingClassId;
        this.publishAt = publishAt;
        this.deadline = deadline;
        this.maxSubmissions = maxSubmissions;
        this.scoringRules = scoringRules;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getTeachingClassId() {
        return teachingClassId;
    }

    public Status getStatus() {
        return status;
    }

    public void withdraw() {
        this.status = Status.WITHDRAWN;
    }

    public void republish() {
        this.status = Status.PUBLISHED;
    }

    public LocalDateTime getPublishAt() {
        return publishAt;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public int getMaxSubmissions() {
        return maxSubmissions;
    }

    public String getScoringRules() {
        return scoringRules;
    }

    public int getVersion() {
        return version;
    }

    public boolean isOpenAt(LocalDateTime now) {
        return status == Status.PUBLISHED
                && (publishAt == null || !now.isBefore(publishAt))
                && (deadline == null || now.isBefore(deadline));
    }

    public void updateRules(LocalDateTime publishAt, LocalDateTime deadline,
                            int maxSubmissions, String scoringRules) {
        if (publishAt != null) {
            this.publishAt = publishAt;
        }
        if (deadline != null) {
            this.deadline = deadline;
        }
        if (maxSubmissions > 0) {
            this.maxSubmissions = maxSubmissions;
        }
        if (scoringRules != null) {
            this.scoringRules = scoringRules;
        }
        this.version++;
    }
}
