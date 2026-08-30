package oj.assignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    List<AssignmentProblem> findByAssignmentIdOrderByOrderNumAsc(Long assignmentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AssignmentProblem ap where ap.assignmentId = :assignmentId")
    void deleteByAssignmentId(@Param("assignmentId") Long assignmentId);
}
