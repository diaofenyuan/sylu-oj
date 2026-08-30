package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface TeachingClassRepository extends JpaRepository<TeachingClass, Long> {

    boolean existsByTermIdAndCourseIdAndCode(Long termId, Long courseId, String code);

    List<TeachingClass> findByTermIdOrderByIdAsc(Long termId);

    List<TeachingClass> findByTermIdAndCourseIdOrderByIdAsc(Long termId, Long courseId);

    List<TeachingClass> findAllByOrderByIdAsc();

    Optional<TeachingClass> findByIdAndTermId(Long id, Long termId);

    /** 初始化班级级系统目录时锁定班级行，避免并发重复建目录。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TeachingClass c where c.id = :id")
    Optional<TeachingClass> findByIdForUpdate(@Param("id") Long id);
}
