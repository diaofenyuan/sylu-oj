package oj.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 考试结束不可变归档：JSONL + SHA-256 校验和（设计 13.2）。 */
@Entity
@Table(name = "exam_archive")
public class ExamArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false, unique = true)
    private Long assignmentId;

    @Column(name = "storage_key", nullable = false, length = 128)
    private String storageKey;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "submission_count", nullable = false)
    private int submissionCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ExamArchive() {
    }

    public ExamArchive(Long assignmentId, String storageKey, String checksum, int submissionCount) {
        this.assignmentId = assignmentId;
        this.storageKey = storageKey;
        this.checksum = checksum;
        this.submissionCount = submissionCount;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getSubmissionCount() {
        return submissionCount;
    }
}
