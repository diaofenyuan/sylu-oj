package oj.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

/**
 * 提交次数计数：以 (AssignmentTarget, 学生) 行锁保证并发下的原子计数与超限拒绝。
 */
@Entity
@Table(name = "submission_counter")
@IdClass(SubmissionCounter.Pk.class)
public class SubmissionCounter {

    public static class Pk implements Serializable {

        private Long assignmentTargetId;
        private Long studentId;

        public Pk() {
        }

        public Pk(Long assignmentTargetId, Long studentId) {
            this.assignmentTargetId = assignmentTargetId;
            this.studentId = studentId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return assignmentTargetId.equals(pk.assignmentTargetId) && studentId.equals(pk.studentId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(assignmentTargetId, studentId);
        }
    }

    @Id
    @Column(name = "assignment_target_id", nullable = false)
    private Long assignmentTargetId;

    @Id
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    protected SubmissionCounter() {
    }

    public SubmissionCounter(Long assignmentTargetId, Long studentId) {
        this.assignmentTargetId = assignmentTargetId;
        this.studentId = studentId;
    }

    public Long getAssignmentTargetId() {
        return assignmentTargetId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int increment() {
        return ++attemptCount;
    }

    /**
     * SE（判题系统错误）不消耗提交次数：回退计数。
     */
    public int decrement() {
        if (attemptCount > 0) {
            attemptCount--;
        }
        return attemptCount;
    }
}
