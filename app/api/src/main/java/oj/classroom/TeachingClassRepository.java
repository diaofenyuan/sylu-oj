package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeachingClassRepository extends JpaRepository<TeachingClass, Long> {

    boolean existsByTermIdAndCourseIdAndCode(Long termId, Long courseId, String code);

    List<TeachingClass> findByTermIdOrderByIdAsc(Long termId);

    List<TeachingClass> findByTermIdAndCourseIdOrderByIdAsc(Long termId, Long courseId);

    List<TeachingClass> findAllByOrderByIdAsc();

    Optional<TeachingClass> findByIdAndTermId(Long id, Long termId);
}
