package oj.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** 组卷条目：题目权重之和必须恰为 100。 */
@Entity
@Table(name = "assignment_problem", indexes = @Index(name = "idx_assignment_problem_problem", columnList = "problem_id"))
public class AssignmentProblem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "order_num", nullable = false)
    private int orderNum;

    @Column(name = "weight", nullable = false)
    private BigDecimal weight;

    protected AssignmentProblem() {
    }

    public AssignmentProblem(Long assignmentId, Long problemId, int orderNum, BigDecimal weight) {
        this.assignmentId = assignmentId;
        this.problemId = problemId;
        this.orderNum = orderNum;
        this.weight = weight;
    }

    @PrePersist
    void onPrePersist() {
        // created_at 列由迁移默认填充，无需赋值
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public int getOrderNum() {
        return orderNum;
    }

    public BigDecimal getWeight() {
        return weight;
    }
}
