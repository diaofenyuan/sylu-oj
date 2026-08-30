package oj.judge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 判题任务：一次提交判题尝试对应一条任务（attempt 区分 SE 自动重试）。
 * 只有 dispatched_at 非空（事件已投递）且状态为 PENDING 的任务可被 Agent 长轮询领取。
 */
@Entity
@Table(name = "judge_task", indexes = {
        @Index(name = "idx_judge_task_claim", columnList = "status, dispatched_at, id"),
        @Index(name = "idx_judge_task_lease", columnList = "status, lease_expires_at"),
        @Index(name = "idx_judge_task_submission", columnList = "submission_id")
})
public class JudgeTask {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_uuid", nullable = false, length = 36)
    private String taskUuid;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "assignment_target_id", nullable = false)
    private Long assignmentTargetId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "snapshot_version", nullable = false)
    private int snapshotVersion;

    @Column(name = "testcase_set_id", nullable = false)
    private Long testcaseSetId;

    @Column(name = "language", nullable = false, length = 16)
    private String language;

    @Column(name = "language_runtime", nullable = false, length = 64)
    private String languageRuntime;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected JudgeTask() {
    }

    public JudgeTask(String taskUuid, Long submissionId, Long assignmentId, Long assignmentTargetId,
                     Long problemId, Long snapshotId, int snapshotVersion, Long testcaseSetId,
                     String language, String languageRuntime, int attempt) {
        this.taskUuid = taskUuid;
        this.submissionId = submissionId;
        this.assignmentId = assignmentId;
        this.assignmentTargetId = assignmentTargetId;
        this.problemId = problemId;
        this.snapshotId = snapshotId;
        this.snapshotVersion = snapshotVersion;
        this.testcaseSetId = testcaseSetId;
        this.language = language;
        this.languageRuntime = languageRuntime;
        this.attempt = attempt;
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

    public String getTaskUuid() {
        return taskUuid;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getAssignmentTargetId() {
        return assignmentTargetId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public int getSnapshotVersion() {
        return snapshotVersion;
    }

    public Long getTestcaseSetId() {
        return testcaseSetId;
    }

    public String getLanguage() {
        return language;
    }

    public String getLanguageRuntime() {
        return languageRuntime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void markClaimed(String agentId, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        this.status = CLAIMED;
        this.claimedBy = agentId;
        this.claimedAt = now;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public void markCompleted() {
        this.status = COMPLETED;
    }

    public void markDispatched(LocalDateTime now) {
        if (this.dispatchedAt == null) {
            this.dispatchedAt = now;
        }
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
