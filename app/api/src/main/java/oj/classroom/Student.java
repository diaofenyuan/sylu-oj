package oj.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 学生（外部身份：学号）。 */
@Entity
@Table(name = "student")
public class Student {

    public enum Status {ACTIVE, DISABLED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_no", nullable = false, unique = true, length = 32)
    private String studentNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Student() {
    }

    public Student(String studentNo, String name) {
        this.studentNo = studentNo;
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

    public String getStudentNo() {
        return studentNo;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public void disable() {
        this.status = Status.DISABLED;
    }
}
