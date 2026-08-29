package oj.submission;

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
 * 学生提交：judge_status 保存最新判题状态（初始 PD），
 * 与 judge_result 在同一事务更新；幂等键防止重放消耗次数。
 */
@Entity
@Table(name = "submission", indexes = {
        @Index(name = "idx_submission_target", columnList = "assignment_target_id"),
        @Index(name = "idx_submission_student", columnList = "student_id")
})
public class Submission {

    public static final String PENDING = "PD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_target_id", nullable = false)
    private Long assignmentTargetId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "language", nullable = false, length = 16)
    private String language;

    @Column(name = "code", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String code;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "judge_status", nullable = false, length = 4)
    private String judgeStatus = PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Submission() {
    }

    public Submission(Long assignmentTargetId, Long problemId, Long studentId,
                     String language, String code, int attemptNo, String idempotencyKey) {
        this.assignmentTargetId = assignmentTargetId;
        this.problemId = problemId;
        this.studentId = studentId;
        this.language = language;
        this.code = code;
        this.attemptNo = attemptNo;
        this.idempotencyKey = idempotencyKey;
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

    public Long getAssignmentTargetId() {
        return assignmentTargetId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getLanguage() {
        return language;
    }

    public String getCode() {
        return code;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getJudgeStatus() {
        return judgeStatus;
    }

    public void updateJudgeStatus(String judgeStatus) {
        this.judgeStatus = judgeStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
