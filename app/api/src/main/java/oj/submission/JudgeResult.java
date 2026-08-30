package oj.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 判题结果（1:1 提交）：固定状态码 + 归一化得分（0-100 两位小数）。
 * result_version 递增，旧版本结果不得覆盖新版本。
 */
@Entity
@Table(name = "judge_result")
public class JudgeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "submission_id", nullable = false, unique = true)
    private Submission submission;

    @Column(name = "result_code", nullable = false, length = 4)
    private String resultCode;

    @Column(name = "normalized_score", nullable = false)
    private BigDecimal normalizedScore;

    @Column(name = "total_time_ms", nullable = false)
    private long totalTimeMs;

    @Column(name = "peak_memory_kb", nullable = false)
    private long peakMemoryKb;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "result_version", nullable = false)
    private int resultVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected JudgeResult() {
    }

    public JudgeResult(Submission submission, String resultCode, BigDecimal normalizedScore,
                       long totalTimeMs, long peakMemoryKb, String agentId, int resultVersion) {
        this.submission = submission;
        this.resultCode = resultCode;
        this.normalizedScore = normalizedScore;
        this.totalTimeMs = totalTimeMs;
        this.peakMemoryKb = peakMemoryKb;
        this.agentId = agentId;
        this.resultVersion = resultVersion;
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

    public Submission getSubmission() {
        return submission;
    }

    public String getResultCode() {
        return resultCode;
    }

    public BigDecimal getNormalizedScore() {
        return normalizedScore;
    }

    public long getTotalTimeMs() {
        return totalTimeMs;
    }

    public long getPeakMemoryKb() {
        return peakMemoryKb;
    }

    public String getAgentId() {
        return agentId;
    }

    public int getResultVersion() {
        return resultVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void applyNewVersion(String resultCode, BigDecimal normalizedScore,
                                long totalTimeMs, long peakMemoryKb, String agentId, int resultVersion) {
        this.resultCode = resultCode;
        this.normalizedScore = normalizedScore;
        this.totalTimeMs = totalTimeMs;
        this.peakMemoryKb = peakMemoryKb;
        this.agentId = agentId;
        this.resultVersion = resultVersion;
    }
}
