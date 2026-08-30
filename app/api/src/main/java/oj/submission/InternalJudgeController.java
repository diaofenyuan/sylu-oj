package oj.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import oj.shared.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部判题结果联调接口（仅 dev/test profile 启用，独立内部令牌保护）。
 * Task 6 起生产结果一律经 /api/judge/v1（mTLS + HMAC 签名 + 幂等校验）进入；
 * 本端点仅作为 dev/内测的判题结果模拟器供既有回归使用，生产禁用。
 */
@RestController
@RequestMapping("/internal/judge")
public class InternalJudgeController {

    private final JudgeResultService judgeResultService;

    public InternalJudgeController(JudgeResultService judgeResultService) {
        this.judgeResultService = judgeResultService;
    }

    public record TestcaseOutcomeDto(int order, String status, java.math.BigDecimal score,
                                     long timeMs, long memoryKb) {
    }

    public record ResultRequest(@NotNull Long submissionId, @NotNull String resultCode,
                                java.math.BigDecimal normalizedScore, long totalTimeMs,
                                long peakMemoryKb, Integer resultVersion, String agentId,
                                java.util.List<TestcaseOutcomeDto> testcases) {
    }

    @PostMapping("/results")
    public Map<String, Object> record(@Valid @RequestBody ResultRequest request) {
        var result = judgeResultService.record(new JudgeResultService.ResultCommand(
                request.submissionId(), request.resultCode(), request.normalizedScore(),
                request.totalTimeMs(), request.peakMemoryKb(),
                request.resultVersion() == null ? 1 : request.resultVersion(),
                request.agentId(),
                request.testcases() == null ? null : request.testcases().stream()
                        .map(t -> new JudgeResultService.TestcaseOutcome(t.order(), t.status(),
                                t.score(), t.timeMs(), t.memoryKb()))
                        .toList()));
        return Map.of("judgeResultId", result.getId(),
                "resultCode", result.getResultCode(),
                "resultVersion", result.getResultVersion());
    }
}
