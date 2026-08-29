package oj.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 测试点结果：状态、得分与资源指标，用于可解释的部分得分。 */
@Entity
@Table(name = "testcase_result")
public class TestcaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "judge_result_id", nullable = false)
    private JudgeResult judgeResult;

    @Column(name = "testcase_order", nullable = false)
    private int testcaseOrder;

    @Column(name = "status", nullable = false, length = 4)
    private String status;

    @Column(name = "score", nullable = false)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "time_ms", nullable = false)
    private long timeMs;

    @Column(name = "memory_kb", nullable = false)
    private long memoryKb;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TestcaseResult() {
    }

    public TestcaseResult(JudgeResult judgeResult, int testcaseOrder, String status,
                          BigDecimal score, long timeMs, long memoryKb) {
        this.judgeResult = judgeResult;
        this.testcaseOrder = testcaseOrder;
        this.status = status;
        this.score = score;
        this.timeMs = timeMs;
        this.memoryKb = memoryKb;
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

    public JudgeResult getJudgeResult() {
        return judgeResult;
    }

    public int getTestcaseOrder() {
        return testcaseOrder;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getScore() {
        return score;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public long getMemoryKb() {
        return memoryKb;
    }
}
