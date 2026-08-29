package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    boolean existsByStaffNo(String staffNo);

    Optional<Teacher> findByStaffNo(String staffNo);

    List<Teacher> findByStatusOrderByNameAsc(Teacher.Status status);
}
