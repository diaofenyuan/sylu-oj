package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    boolean existsByCode(String code);

    List<Course> findByOrderByNameAsc();

    Optional<Course> findByCode(String code);
}
