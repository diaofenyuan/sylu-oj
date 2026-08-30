package oj.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 随机抽查记录（≥5%，选样种子留痕，设计 13.3）。 */
@Entity
@Table(name = "exam_spot_check")
public class ExamSpotCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "seed", nullable = false, length = 64)
    private String seed;

    @Column(name = "sampled_at", nullable = false)
    private LocalDateTime sampledAt;

    protected ExamSpotCheck() {
    }

    public ExamSpotCheck(Long assignmentId, Long submissionId, String seed) {
        this.assignmentId = assignmentId;
        this.submissionId = submissionId;
        this.seed = seed;
    }

    @PrePersist
    void onPrePersist() {
        if (sampledAt == null) {
            sampledAt = LocalDateTime.now();
        }
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public String getSeed() {
        return seed;
    }
}
