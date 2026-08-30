package oj.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamAppealRepository extends JpaRepository<ExamAppeal, Long> {

    Optional<ExamAppeal> findFirstBySubmissionIdAndStatusOrderByCreatedAtDesc(Long submissionId, String status);

    Optional<ExamAppeal> findFirstBySubmissionIdAndStatusInOrderByCreatedAtDesc(Long submissionId, List<String> statuses);

    List<ExamAppeal> findByAssignmentIdOrderByIdDesc(Long assignmentId);
}
