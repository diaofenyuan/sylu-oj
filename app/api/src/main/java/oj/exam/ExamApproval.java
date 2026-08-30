package oj.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 双人审批（设计 13.1）：考试期间的题目/成绩/配置修改与考试锁定/解锁，
 * 必须由第二人批准（与请求人不同），且批准人须持有已确认 TOTP（二次认证）。
 */
@Entity
@Table(name = "exam_approval")
public class ExamApproval {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "payload", columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    protected ExamApproval() {
    }

    public ExamApproval(Long assignmentId, String action, String payload, String reason, Long requestedBy) {
        this.assignmentId = assignmentId;
        this.action = action;
        this.payload = payload;
        this.reason = reason;
        this.requestedBy = requestedBy;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void approve(Long approverId) {
        this.approvedBy = approverId;
        this.status = APPROVED;
        this.decidedAt = LocalDateTime.now();
    }

    public void reject(Long approverId) {
        this.approvedBy = approverId;
        this.status = REJECTED;
        this.decidedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getAction() {
        return action;
    }

    public String getPayload() {
        return payload;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public String getStatus() {
        return status;
    }
}
