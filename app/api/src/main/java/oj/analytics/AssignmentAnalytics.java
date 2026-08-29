package oj.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 只读分析视图 v_assignment_analytics：按 (AssignmentTarget, 学生) 汇总
 * 总分（题目加权最高有效提交分）、满分题数、已计分题数、提交次数。
 */
@Entity
@Immutable
@Table(name = "v_assignment_analytics")
@IdClass(AssignmentAnalytics.Pk.class)
public class AssignmentAnalytics {

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
            return Objects.equals(assignmentTargetId, pk.assignmentTargetId)
                    && Objects.equals(studentId, pk.studentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assignmentTargetId, studentId);
        }
    }

    @Id
    @Column(name = "assignment_target_id", nullable = false)
    private Long assignmentTargetId;

    @Id
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "student_no", nullable = false)
    private String studentNo;

    @Column(name = "problems_total", nullable = false)
    private long problemsTotal;

    @Column(name = "total_score", nullable = false)
    private BigDecimal totalScore;

    @Column(name = "ac_problems", nullable = false)
    private long acProblems;

    @Column(name = "problems_scored", nullable = false)
    private long problemsScored;

    @Column(name = "submission_count", nullable = false)
    private long submissionCount;

    public Long getAssignmentTargetId() {
        return assignmentTargetId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public long getProblemsTotal() {
        return problemsTotal;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public long getAcProblems() {
        return acProblems;
    }

    public long getProblemsScored() {
        return problemsScored;
    }

    public long getSubmissionCount() {
        return submissionCount;
    }
}
