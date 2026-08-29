package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MajorRepository extends JpaRepository<Major, Long> {

    boolean existsByCode(String code);

    List<Major> findByOrderByNameAsc();

    Optional<Major> findByCode(String code);
}
