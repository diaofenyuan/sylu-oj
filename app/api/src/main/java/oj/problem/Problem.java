package oj.problem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 编程题：编辑产生新 version 与新 TestcaseSet；已发布作业通过 ProblemSnapshot
 * 冻结历史版本，题库后续修改不影响已发布作业的判题语义。
 */
@Entity
@Table(name = "problem", indexes = @Index(name = "idx_problem_bank", columnList = "problem_bank_id"))
public class Problem {

    public enum Status {DRAFT, PUBLISHED, ARCHIVED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_bank_id", nullable = false)
    private Long problemBankId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "description", columnDefinition = "MEDIUMTEXT")
    private String description;

    /** 逗号分隔的 C,CPP,PYTHON,JAVA 子集。 */
    @Column(name = "languages", nullable = false, length = 128)
    private String languages;

    @Column(name = "difficulty", nullable = false, length = 16)
    private String difficulty = "EASY";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs = 10000;

    @Column(name = "memory_limit_mb", nullable = false)
    private int memoryLimitMb = 256;

    @Column(name = "output_limit_kb", nullable = false)
    private int outputLimitKb = 65536;

    @Column(name = "max_score", nullable = false)
    private BigDecimal maxScore = new BigDecimal("100.00");

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Problem() {
    }

    public Problem(Long problemBankId, String code, String title, String description,
                   String languages, String difficulty, int timeLimitMs, int memoryLimitMb,
                   int outputLimitKb, BigDecimal maxScore, Long createdBy) {
        this.problemBankId = problemBankId;
        this.code = code;
        this.title = title;
        this.description = description;
        this.languages = languages;
        this.difficulty = difficulty;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.outputLimitKb = outputLimitKb;
        this.maxScore = maxScore;
        this.createdBy = createdBy;
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

    public Long getProblemBankId() {
        return problemBankId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public String getLanguages() {
        return languages;
    }

    public void updateLanguages(String languages) {
        this.languages = languages;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public Status getStatus() {
        return status;
    }

    public void publish() {
        this.status = Status.PUBLISHED;
    }

    public int getVersion() {
        return version;
    }

    public void bumpVersion() {
        this.version++;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public void updateTimeLimitMs(int timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void updateMemoryLimitMb(int memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public int getOutputLimitKb() {
        return outputLimitKb;
    }

    public void updateOutputLimitKb(int outputLimitKb) {
        this.outputLimitKb = outputLimitKb;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
