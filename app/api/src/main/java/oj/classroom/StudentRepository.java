package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    boolean existsByStudentNo(String studentNo);

    Optional<Student> findByStudentNo(String studentNo);

    List<Student> findByStatusOrderByNameAsc(Student.Status status);

    List<Student> findByStudentNoContainingOrNameContainingOrderById(String noPart, String namePart);
}
