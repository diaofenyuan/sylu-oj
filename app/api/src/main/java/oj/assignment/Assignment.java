package oj.assignment;

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

import java.time.LocalDateTime;

/**
 * 作业/试卷：mode=HOMEWORK 允许受控修改目标班级规则；
 * mode=EXAM 发布后锁定题目、测试数据、目标班级规则与判题运行时。
 */
@Entity
@Table(name = "assignment", indexes = @Index(name = "idx_assignment_creator", columnList = "created_by"))
public class Assignment {

    public enum Mode {HOMEWORK, EXAM}

    public enum Status {DRAFT, PUBLISHED, WITHDRAWN}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "exam_locked", nullable = false)
    private boolean examLocked = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Assignment() {
    }

    public Assignment(String title, Mode mode, Long createdBy) {
        this.title = title;
        this.mode = mode;
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

    public String getTitle() {
        return title;
    }

    public Mode getMode() {
        return mode;
    }

    public Status getStatus() {
        return status;
    }

    public void publish() {
        this.status = Status.PUBLISHED;
    }

    public void withdraw() {
        this.status = Status.WITHDRAWN;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public boolean isExamLocked() {
        return mode == Mode.EXAM && status == Status.PUBLISHED && examLocked;
    }

    public void markExamLocked() {
        this.examLocked = true;
    }
}
