package oj.problem;

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
 * 测试用例：is_sample=true 为公开样例（学生可见题面部分），
 * 隐藏用例仅进入判题沙箱按需分发（Task 6）。
 */
@Entity
@Table(name = "testcase")
public class Testcase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "testcase_set_id", nullable = false)
    private Long testcaseSetId;

    @Column(name = "order_num", nullable = false)
    private int orderNum;

    @Column(name = "is_sample", nullable = false)
    private boolean sample;

    @Column(name = "input", columnDefinition = "MEDIUMTEXT")
    private String input;

    @Column(name = "expected_output", columnDefinition = "MEDIUMTEXT")
    private String expectedOutput;

    @Column(name = "score", nullable = false)
    private BigDecimal score = new BigDecimal("10.00");

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Testcase() {
    }

    public Testcase(Long testcaseSetId, int orderNum, boolean sample,
                    String input, String expectedOutput, BigDecimal score) {
        this.testcaseSetId = testcaseSetId;
        this.orderNum = orderNum;
        this.sample = sample;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.score = score;
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

    public Long getTestcaseSetId() {
        return testcaseSetId;
    }

    public int getOrderNum() {
        return orderNum;
    }

    public boolean isSample() {
        return sample;
    }

    public String getInput() {
        return input;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public BigDecimal getScore() {
        return score;
    }
}
