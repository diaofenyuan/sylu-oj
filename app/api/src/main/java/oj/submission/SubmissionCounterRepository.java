package oj.submission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface SubmissionCounterRepository extends JpaRepository<SubmissionCounter, SubmissionCounter.Pk> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SubmissionCounter c where c.assignmentTargetId = :targetId and c.studentId = :studentId")
    Optional<SubmissionCounter> lockCounter(@Param("targetId") Long targetId, @Param("studentId") Long studentId);
}
