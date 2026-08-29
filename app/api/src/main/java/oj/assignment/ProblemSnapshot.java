package oj.assignment;

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
 * 发布时冻结的题目快照：题面、判题配置与测试数据版本。
 * 题库后续修改不得改变已发布作业的判题语义。
 */
@Entity
@Table(name = "problem_snapshot", indexes = @Index(name = "idx_problem_snapshot_assignment", columnList = "assignment_id"))
public class ProblemSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "problem_version", nullable = false)
    private int problemVersion;

    @Column(name = "testcase_set_id", nullable = false)
    private Long testcaseSetId;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "description", columnDefinition = "MEDIUMTEXT")
    private String description;

    @Column(name = "languages", nullable = false, length = 128)
    private String languages;

    /** JSON：timeLimitMs/memoryLimitMb/outputLimitKb/maxScore。 */
    @Column(name = "judge_config", nullable = false, length = 1024)
    private String judgeConfig;

    @Column(name = "content_checksum", nullable = false, length = 64)
    private String contentChecksum;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProblemSnapshot() {
    }

    public ProblemSnapshot(Long assignmentId, Long problemId, int problemVersion, Long testcaseSetId,
                           String title, String description, String languages, String judgeConfig,
                           String contentChecksum) {
        this.assignmentId = assignmentId;
        this.problemId = problemId;
        this.problemVersion = problemVersion;
        this.testcaseSetId = testcaseSetId;
        this.title = title;
        this.description = description;
        this.languages = languages;
        this.judgeConfig = judgeConfig;
        this.contentChecksum = contentChecksum;
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

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public int getProblemVersion() {
        return problemVersion;
    }

    public Long getTestcaseSetId() {
        return testcaseSetId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLanguages() {
        return languages;
    }

    public String getJudgeConfig() {
        return judgeConfig;
    }

    public String getContentChecksum() {
        return contentChecksum;
    }
}
