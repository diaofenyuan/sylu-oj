package oj.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JudgeResultRepository extends JpaRepository<JudgeResult, Long> {

    Optional<JudgeResult> findBySubmissionId(Long submissionId);

    boolean existsBySubmissionId(Long submissionId);
}
