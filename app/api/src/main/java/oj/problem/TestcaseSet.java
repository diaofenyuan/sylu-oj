package oj.problem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 测试集：题目的一个版本；编辑用例生成新版本，历史版本被快照引用后不可变。 */
@Entity
@Table(name = "testcase_set")
public class TestcaseSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TestcaseSet() {
    }

    public TestcaseSet(Long problemId, int version) {
        this.problemId = problemId;
        this.version = version;
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

    public Long getProblemId() {
        return problemId;
    }

    public int getVersion() {
        return version;
    }
}
