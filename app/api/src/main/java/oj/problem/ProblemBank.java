package oj.problem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 题库：归属教学班，教师按授课关系访问。 */
@Entity
@Table(name = "problem_bank")
public class ProblemBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teaching_class_id", nullable = false)
    private Long teachingClassId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProblemBank() {
    }

    public ProblemBank(Long teachingClassId, String name, String description) {
        this.teachingClassId = teachingClassId;
        this.name = name;
        this.description = description;
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

    public Long getTeachingClassId() {
        return teachingClassId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
