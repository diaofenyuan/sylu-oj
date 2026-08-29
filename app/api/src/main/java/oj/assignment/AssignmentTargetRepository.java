package oj.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssignmentTargetRepository extends JpaRepository<AssignmentTarget, Long> {

    List<AssignmentTarget> findByAssignmentIdOrderByIdAsc(Long assignmentId);

    Optional<AssignmentTarget> findByAssignmentIdAndTeachingClassId(Long assignmentId, Long teachingClassId);

    List<AssignmentTarget> findByTeachingClassIdAndStatusOrderByIdAsc(Long teachingClassId, AssignmentTarget.Status status);

    List<AssignmentTarget> findByTeachingClassIdOrderByIdAsc(Long teachingClassId);
}
