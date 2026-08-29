package oj.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestcaseResultRepository extends JpaRepository<TestcaseResult, Long> {

    List<TestcaseResult> findByJudgeResultIdOrderByTestcaseOrderAsc(Long judgeResultId);

    void deleteByJudgeResultId(Long judgeResultId);
}
