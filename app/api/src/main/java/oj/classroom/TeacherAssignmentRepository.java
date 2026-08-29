package oj.classroom;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeacherAssignmentRepository extends JpaRepository<TeacherAssignment, Long> {

    boolean existsByTeachingClassIdAndTeacherIdAndActiveMarkerIsNotNull(Long teachingClassId, Long teacherId);

    List<TeacherAssignment> findByTeacherIdAndActiveMarkerIsNotNullOrderByIdDesc(Long teacherId);

    List<TeacherAssignment> findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(Long teachingClassId);

    List<TeacherAssignment> findByTeachingClassIdOrderByIdAsc(Long teachingClassId);

    long countByTeachingClassIdAndRoleAndActiveMarkerIsNotNull(Long teachingClassId, TeacherAssignment.Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ta from TeacherAssignment ta where ta.id = :id")
    Optional<TeacherAssignment> findByIdForUpdate(@Param("id") Long id);
}
