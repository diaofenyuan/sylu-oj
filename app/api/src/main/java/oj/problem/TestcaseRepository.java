package oj.problem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestcaseRepository extends JpaRepository<Testcase, Long> {

    List<Testcase> findByTestcaseSetIdOrderByOrderNumAsc(Long testcaseSetId);

    List<Testcase> findByTestcaseSetIdAndSampleOrderByOrderNumAsc(Long testcaseSetId, boolean sample);

    Optional<Testcase> findByTestcaseSetIdAndOrderNum(Long testcaseSetId, int orderNum);
}
