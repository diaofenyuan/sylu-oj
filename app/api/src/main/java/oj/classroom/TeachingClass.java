package oj.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 教学班：同一 Course + Term 下可有多个相互独立的教学班，构成独立授权边界。
 */
@Entity
@Table(name = "teaching_class", indexes = {
        @Index(name = "idx_teaching_class_term", columnList = "term_id"),
        @Index(name = "idx_teaching_class_course", columnList = "course_id")
})
public class TeachingClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "term_id", nullable = false)
    private Long termId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "major_id")
    private Long majorId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TeachingClass() {
    }

    public TeachingClass(Long termId, Long courseId, Long majorId, String code, String name) {
        this.termId = termId;
        this.courseId = courseId;
        this.majorId = majorId;
        this.code = code;
        this.name = name;
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

    public Long getTermId() {
        return termId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getMajorId() {
        return majorId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
