package oj.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamLockRepository extends JpaRepository<ExamLock, Long> {

    Optional<ExamLock> findByAssignmentId(Long assignmentId);
}
