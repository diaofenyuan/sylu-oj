package oj.student;

import oj.submission.JudgeResult;
import oj.submission.JudgeResultRepository;
import oj.submission.Submission;
import oj.submission.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderboardServiceTest {
    private SubmissionRepository submissions;
    private JudgeResultRepository results;
    private LeaderboardService service;

    @BeforeEach
    void setup() {
        submissions = mock(SubmissionRepository.class);
        results = mock(JudgeResultRepository.class);
        service = new LeaderboardService(submissions, results);
    }

    private Submission entry(long id, long studentId, long time, long memory) {
        Submission submission = mock(Submission.class);
        when(submission.getId()).thenReturn(id);
        when(submission.getStudentId()).thenReturn(studentId);
        when(submission.getLanguage()).thenReturn("CPP");
        when(submission.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 1, 1, 12, 0));
        JudgeResult result = mock(JudgeResult.class);
        when(result.getTotalTimeMs()).thenReturn(time);
        when(result.getPeakMemoryKb()).thenReturn(memory);
        when(results.findBySubmissionId(id)).thenReturn(Optional.of(result));
        return submission;
    }

    @Test
    void each_student_gets_one_independent_best_entry_before_limit() {
        var entries = List.of(entry(1, 1, 10, 500), entry(2, 1, 20, 100), entry(3, 2, 30, 200));
        when(submissions.findByProblemIdAndJudgeStatus(1L, "AC")).thenReturn(entries);
        var response = service.getLeaderboard(1L, 2);
        assertThat(response.byTime()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(1L, 3L);
        assertThat(response.byMemory()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(2L, 3L);
    }

    @Test
    void unknown_metrics_do_not_win_but_zero_milliseconds_is_valid() {
        var entries = List.of(entry(1, 1, 0, 0), entry(2, 2, -1, -1), entry(3, 3, 10, 100));
        when(submissions.findByProblemIdAndJudgeStatus(1L, "AC")).thenReturn(entries);
        var response = service.getLeaderboard(1L, 50);
        assertThat(response.byTime()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(1L, 3L);
        assertThat(response.byMemory()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(3L);
    }

    @Test
    void ties_are_stable_regardless_of_repository_order() {
        var entries = List.of(entry(3, 3, 10, 100), entry(2, 2, 10, 100), entry(1, 1, 10, 100));
        when(submissions.findByProblemIdAndJudgeStatus(1L, "AC")).thenReturn(entries);
        var response = service.getLeaderboard(1L, 2);
        assertThat(response.byTime()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(1L, 2L);
        assertThat(response.byMemory()).extracting(LeaderboardService.LeaderboardEntry::submissionId)
                .containsExactly(1L, 2L);
    }

    @Test
    void missing_results_are_ignored_and_invalid_limit_uses_default() {
        Submission missing = entry(1, 1, 10, 100);
        when(results.findBySubmissionId(1L)).thenReturn(Optional.empty());
        var entries = List.of(missing, entry(2, 2, 20, 200));
        when(submissions.findByProblemIdAndJudgeStatus(1L, "AC")).thenReturn(entries);
        var response = service.getLeaderboard(1L, -1);
        assertThat(response.byTime()).hasSize(1);
        assertThat(response.byMemory()).hasSize(1);
    }
}
