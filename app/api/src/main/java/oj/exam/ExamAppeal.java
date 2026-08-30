package oj.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 申诉复判（设计 13.3）：复判差异不得自动采用任一版本，
 * 状态停在 REJUDGED 等待人工复核（身份与依据写入审计）后 RESOLVED。
 */
@Entity
@Table(name = "exam_appeal")
public class ExamAppeal {

    public static final String PENDING = "PENDING";
    public static final String REJUDGED = "REJUDGED";
    public static final String RESOLVED = "RESOLVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "original_code", length = 4)
    private String originalCode;

    @Column(name = "original_score", precision = 7, scale = 2)
    private BigDecimal originalScore;

    @Column(name = "rejudged_code", length = 4)
    private String rejudgedCode;

    @Column(name = "rejudged_score", precision = 7, scale = 2)
    private BigDecimal rejudgedScore;

    @Column(name = "diff_note", length = 512)
    private String diffNote;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    protected ExamAppeal() {
    }

    public ExamAppeal(Long assignmentId, Long submissionId, Long requestedBy, String reason,
                      String originalCode, BigDecimal originalScore) {
        this.assignmentId = assignmentId;
        this.submissionId = submissionId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.originalCode = originalCode;
        this.originalScore = originalScore;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void applyRejudgeOutcome(String code, BigDecimal score) {
        this.rejudgedCode = code;
        this.rejudgedScore = score;
        this.diffNote = code.equals(originalCode) && score.compareTo(originalScore) == 0
                ? "复判与原判一致"
                : "复判差异：原 " + originalCode + "/" + originalScore + " → 复判 " + code + "/" + score;
        this.status = REJUDGED;
    }

    public void resolve(Long resolvedBy) {
        this.resolvedBy = resolvedBy;
        this.status = RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void reject() {
        this.status = REJECTED;
        this.resolvedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public String getStatus() {
        return status;
    }

    public String getOriginalCode() {
        return originalCode;
    }

    public BigDecimal getOriginalScore() {
        return originalScore;
    }

    public String getRejudgedCode() {
        return rejudgedCode;
    }

    public BigDecimal getRejudgedScore() {
        return rejudgedScore;
    }

    public String getDiffNote() {
        return diffNote;
    }
}
