package oj.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProblemSnapshotRepository extends JpaRepository<ProblemSnapshot, Long> {

    List<ProblemSnapshot> findByAssignmentIdOrderByProblemIdAsc(Long assignmentId);

    Optional<ProblemSnapshot> findByAssignmentIdAndProblemId(Long assignmentId, Long problemId);
}
