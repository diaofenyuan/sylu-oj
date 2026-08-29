package oj.problem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestcaseSetRepository extends JpaRepository<TestcaseSet, Long> {

    Optional<TestcaseSet> findFirstByProblemIdOrderByVersionDesc(Long problemId);

    List<TestcaseSet> findByProblemIdOrderByVersionDesc(Long problemId);
}
