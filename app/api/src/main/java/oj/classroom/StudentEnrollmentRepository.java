package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface StudentEnrollmentRepository extends JpaRepository<StudentEnrollment, Long> {

    Optional<StudentEnrollment> findByStudentIdAndActiveMarkerIsNotNull(Long studentId);

    Optional<StudentEnrollment> findByStudentIdAndTeachingClassIdAndActiveMarkerIsNotNull(Long studentId, Long teachingClassId);

    List<StudentEnrollment> findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(Long teachingClassId);

    List<StudentEnrollment> findByStudentIdOrderByIdDesc(Long studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select se from StudentEnrollment se where se.studentId = :studentId and se.activeMarker is not null")
    Optional<StudentEnrollment> findActiveByStudentIdForUpdate(@Param("studentId") Long studentId);
}
