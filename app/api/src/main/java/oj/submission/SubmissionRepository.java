package oj.submission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    Optional<Submission> findByAssignmentTargetIdAndProblemIdAndStudentIdAndIdempotencyKey(
            Long assignmentTargetId, Long problemId, Long studentId, String idempotencyKey);

    List<Submission> findByAssignmentTargetIdAndStudentIdOrderByCreatedAtAsc(Long assignmentTargetId, Long studentId);

    List<Submission> findByAssignmentTargetIdAndProblemIdAndStudentIdOrderByCreatedAtAsc(
            Long assignmentTargetId, Long problemId, Long studentId);

    List<Submission> findByAssignmentTargetIdOrderByIdAsc(Long assignmentTargetId);

    long countByAssignmentTargetIdAndStudentId(Long assignmentTargetId, Long studentId);

    List<Submission> findByProblemIdAndJudgeStatus(Long problemId, String judgeStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.id = :id")
    Optional<Submission> findByIdForUpdate(@Param("id") Long id);
}
