package oj.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByCreatedByOrderByIdDesc(Long createdBy);

    List<Assignment> findByStatusOrderByIdDesc(Assignment.Status status);
}
