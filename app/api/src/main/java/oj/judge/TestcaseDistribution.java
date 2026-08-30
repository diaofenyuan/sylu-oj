package oj.judge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 测试数据分发记录：逐用例审计，同时作为异常拉取（错配/超速）检测依据。
 */
@Entity
@Table(name = "testcase_distribution", indexes = {
        @Index(name = "idx_testcase_distribution_agent", columnList = "agent_id, distributed_at"),
        @Index(name = "idx_testcase_distribution_task", columnList = "task_uuid")
})
public class TestcaseDistribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "task_uuid", nullable = false, length = 36)
    private String taskUuid;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "testcase_order", nullable = false)
    private int testcaseOrder;

    @Column(name = "matched", nullable = false)
    private boolean matched = true;

    @Column(name = "distributed_at", nullable = false)
    private LocalDateTime distributedAt;

    protected TestcaseDistribution() {
    }

    public TestcaseDistribution(String agentId, String taskUuid, Long problemId,
                                int testcaseOrder, boolean matched) {
        this.agentId = agentId;
        this.taskUuid = taskUuid;
        this.problemId = problemId;
        this.testcaseOrder = testcaseOrder;
        this.matched = matched;
    }

    @PrePersist
    void onPrePersist() {
        if (distributedAt == null) {
            distributedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTaskUuid() {
        return taskUuid;
    }

    public Long getProblemId() {
        return problemId;
    }

    public int getTestcaseOrder() {
        return testcaseOrder;
    }

    public boolean isMatched() {
        return matched;
    }

    public LocalDateTime getDistributedAt() {
        return distributedAt;
    }
}
