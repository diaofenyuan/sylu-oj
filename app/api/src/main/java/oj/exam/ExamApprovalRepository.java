package oj.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamApprovalRepository extends JpaRepository<ExamApproval, Long> {

    Optional<ExamApproval> findFirstByAssignmentIdAndActionAndStatusOrderByDecidedAtDesc(
            Long assignmentId, String action, String status);

    List<ExamApproval> findByAssignmentIdOrderByIdDesc(Long assignmentId);
}
