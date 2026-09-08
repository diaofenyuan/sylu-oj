package oj.student;

import oj.submission.JudgeResult;
import oj.submission.JudgeResultRepository;
import oj.submission.Submission;
import oj.submission.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 性能排行榜服务：展示题目的最优解（按时间或内存排序）
 */
@Service
public class LeaderboardService {

    private final SubmissionRepository submissionRepository;
    private final JudgeResultRepository judgeResultRepository;

    public LeaderboardService(SubmissionRepository submissionRepository,
                             JudgeResultRepository judgeResultRepository) {
        this.submissionRepository = submissionRepository;
        this.judgeResultRepository = judgeResultRepository;
    }

    public record LeaderboardEntry(
            Long submissionId,
            String studentName,
            String language,
            long timeMs,
            long memoryKb,
            String submittedAt
    ) {}

    public record LeaderboardResponse(
            List<LeaderboardEntry> byTime,
            List<LeaderboardEntry> byMemory
    ) {}

    /**
     * 获取指定题目的性能排行榜
     * @param problemId 题目ID
     * @param limit 返回条数（默认100）
     */
    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(Long problemId, int limit) {
        if (limit <= 0 || limit > 100) {
            limit = 100;
        }

        // 查询所有AC的提交
        List<Submission> acSubmissions = submissionRepository.findByProblemIdAndJudgeStatus(
                problemId, "AC");

        // 过滤出有判题结果的提交
        List<SubmissionWithResult> submissions = acSubmissions.stream()
                .map(s -> {
                    JudgeResult result = judgeResultRepository.findBySubmissionId(s.getId()).orElse(null);
                    if (result != null) {
                        return new SubmissionWithResult(s, result);
                    }
                    return null;
                })
                .filter(sr -> sr != null)
                .collect(Collectors.toList());

        // 两个榜单分别选优，同一学生的最快提交不一定也是最省内存的提交。
        List<LeaderboardEntry> byTime = rank(submissions, limit, false);
        List<LeaderboardEntry> byMemory = rank(submissions, limit, true);

        return new LeaderboardResponse(byTime, byMemory);
    }

    private List<LeaderboardEntry> rank(List<SubmissionWithResult> submissions, int limit, boolean byMemory) {
        Set<Long> students = new HashSet<>();
        return submissions.stream()
                // 内存为 0 或负数表示指标不可用，不能将其作为最省内存；0ms 是有效耗时。
                .filter(sr -> byMemory ? sr.result.getPeakMemoryKb() > 0 : sr.result.getTotalTimeMs() >= 0)
                .sorted(Comparator.comparingLong((SubmissionWithResult sr) -> byMemory
                                ? sr.result.getPeakMemoryKb() : sr.result.getTotalTimeMs())
                        .thenComparing(sr -> sr.submission.getCreatedAt())
                        .thenComparing(sr -> sr.submission.getId()))
                .filter(sr -> students.add(sr.submission.getStudentId()))
                .limit(limit)
                .map(sr -> toEntry(sr.submission, sr.result))
                .toList();
    }

    private LeaderboardEntry toEntry(Submission submission, JudgeResult result) {
        // 隐藏学生姓名，只显示学号或昵称
        String displayName = maskStudentName(submission.getStudentId());
        return new LeaderboardEntry(
                submission.getId(),
                displayName,
                submission.getLanguage(),
                result.getTotalTimeMs(),
                result.getPeakMemoryKb(),
                submission.getCreatedAt().toString()
        );
    }

    private String maskStudentName(Long studentId) {
        // 这里可以从数据库查询学生信息
        // 为了隐私，只显示部分信息
        return "学生" + (studentId % 1000);
    }

    private static class SubmissionWithResult {
        final Submission submission;
        final JudgeResult result;

        SubmissionWithResult(Submission submission, JudgeResult result) {
            this.submission = submission;
            this.result = result;
        }
    }
}
