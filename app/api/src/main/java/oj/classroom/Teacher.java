package oj.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 教师（外部身份：教务工号）。 */
@Entity
@Table(name = "teacher", indexes = @Index(name = "idx_teacher_status", columnList = "status"))
public class Teacher {

    public enum Status {ACTIVE, DISABLED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_no", nullable = false, unique = true, length = 32)
    private String staffNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Teacher() {
    }

    public Teacher(String staffNo, String name) {
        this.staffNo = staffNo;
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

    public String getStaffNo() {
        return staffNo;
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
