package oj.problem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProblemBankRepository extends JpaRepository<ProblemBank, Long> {

    List<ProblemBank> findByTeachingClassIdOrderByIdAsc(Long teachingClassId);

    Optional<ProblemBank> findByIdAndTeachingClassId(Long id, Long teachingClassId);

    boolean existsByTeachingClassIdAndName(Long teachingClassId, String name);
}
