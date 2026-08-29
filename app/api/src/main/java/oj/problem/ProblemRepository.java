package oj.problem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByProblemBankIdOrderByIdAsc(Long problemBankId);

    Optional<Problem> findByIdAndProblemBankId(Long id, Long problemBankId);

    boolean existsByProblemBankIdAndCode(Long problemBankId, String code);

    List<Problem> findByIdIn(Collection<Long> ids);

    long countByIdInAndStatus(Collection<Long> ids, Problem.Status status);
}
