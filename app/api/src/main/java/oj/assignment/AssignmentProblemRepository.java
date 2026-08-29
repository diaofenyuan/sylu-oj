package oj.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    List<AssignmentProblem> findByAssignmentIdOrderByOrderNumAsc(Long assignmentId);
}
