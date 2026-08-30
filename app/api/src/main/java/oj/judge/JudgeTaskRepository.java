package oj.judge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface JudgeTaskRepository extends JpaRepository<JudgeTask, Long> {

    Optional<JudgeTask> findByTaskUuid(String taskUuid);

    Optional<JudgeTask> findBySubmissionIdAndAttempt(Long submissionId, int attempt);

    Optional<JudgeTask> findFirstBySubmissionIdOrderByAttemptDesc(Long submissionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<JudgeTask> findFirstByStatusAndDispatchedAtIsNotNullOrderByIdAsc(String status);

    @Modifying(clearAutomatically = true)
    @Query("update JudgeTask t set t.status = 'PENDING', t.claimedBy = null, t.claimedAt = null, " +
            "t.leaseExpiresAt = null, t.updatedAt = :now " +
            "where t.status = 'CLAIMED' and t.leaseExpiresAt < :now")
    int requeueExpiredLeases(@Param("now") LocalDateTime now);
}
