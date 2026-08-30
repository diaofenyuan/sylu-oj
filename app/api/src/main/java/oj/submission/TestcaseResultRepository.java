package oj.submission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestcaseResultRepository extends JpaRepository<TestcaseResult, Long> {

    List<TestcaseResult> findByJudgeResultIdOrderByTestcaseOrderAsc(Long judgeResultId);

    // 立即执行的批量删除：派生删除会延迟到 flush 且 insert 先于 delete 执行，
    // 重判覆盖时将触发 (judge_result_id, testcase_order) 唯一键冲突
    @Modifying
    @Query("delete from TestcaseResult t where t.judgeResult.id = :judgeResultId")
    void deleteByJudgeResultId(@Param("judgeResultId") Long judgeResultId);
}
