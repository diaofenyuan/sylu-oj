package oj.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 成绩导出任务：QUEUED → GENERATING → READY/FAILED；文件保留期结束后转 EXPIRED。
 * 记录请求人、目标班级、格式、筛选摘要、匹配数量、文件摘要与审计关联。
 */
@Entity
@Table(name = "grade_export", indexes = {
        @Index(name = "idx_grade_export_target", columnList = "assignment_target_id"),
        @Index(name = "idx_grade_export_status", columnList = "status, expires_at")
})
public class GradeExport {

    public enum Format {XLSX, CSV}

    public enum Status {QUEUED, GENERATING, READY, FAILED, EXPIRED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "assignment_target_id", nullable = false)
    private Long assignmentTargetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private Format format;

    @Column(name = "filter_student_no", length = 32)
    private String filterStudentNo;

    @Column(name = "filter_name_keyword", length = 64)
    private String filterNameKeyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.QUEUED;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "file_checksum", length = 64)
    private String fileChecksum;

    @Column(name = "storage_key", length = 128)
    private String storageKey;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    protected GradeExport() {
    }

    public GradeExport(Long requestedBy, Long assignmentTargetId, Format format,
                       String filterStudentNo, String filterNameKeyword,
                       LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.requestedBy = requestedBy;
        this.assignmentTargetId = assignmentTargetId;
        this.format = format;
        this.filterStudentNo = filterStudentNo;
        this.filterNameKeyword = filterNameKeyword;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
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

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Long getAssignmentTargetId() {
        return assignmentTargetId;
    }

    public Format getFormat() {
        return format;
    }

    public String getFilterStudentNo() {
        return filterStudentNo;
    }

    public String getFilterNameKeyword() {
        return filterNameKeyword;
    }

    public Status getStatus() {
        return status;
    }

    public void markGenerating() {
        this.status = Status.GENERATING;
    }

    public void markReady(int matchCount, String storageKey, String fileChecksum) {
        this.status = Status.READY;
        this.matchCount = matchCount;
        this.storageKey = storageKey;
        this.fileChecksum = fileChecksum;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String errorCode) {
        this.status = Status.FAILED;
        this.errorCode = errorCode;
        this.completedAt = LocalDateTime.now();
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isFileAlive(LocalDateTime now) {
        return status == Status.READY && expiresAt != null && now.isBefore(expiresAt);
    }
}
