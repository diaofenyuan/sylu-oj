package oj.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 考试锁定记录：冻结判题运行时标识与策略校验和（设计 13.1）。 */
@Entity
@Table(name = "exam_lock")
public class ExamLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false, unique = true)
    private Long assignmentId;

    @Column(name = "locked_by", nullable = false)
    private Long lockedBy;

    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;

    @Column(name = "runtime_ids", nullable = false, length = 256)
    private String runtimeIds;

    @Column(name = "policy_checksum", nullable = false, length = 64)
    private String policyChecksum;

    @Column(name = "reason", length = 512)
    private String reason;

    protected ExamLock() {
    }

    public ExamLock(Long assignmentId, Long lockedBy, String runtimeIds,
                    String policyChecksum, String reason) {
        this.assignmentId = assignmentId;
        this.lockedBy = lockedBy;
        this.runtimeIds = runtimeIds;
        this.policyChecksum = policyChecksum;
        this.reason = reason;
    }

    @PrePersist
    void onPrePersist() {
        if (lockedAt == null) {
            lockedAt = LocalDateTime.now();
        }
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getRuntimeIds() {
        return runtimeIds;
    }

    public String getPolicyChecksum() {
        return policyChecksum;
    }
}
