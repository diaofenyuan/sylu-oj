package oj.judge;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Judge Gateway（/api/judge/v1）：
 * - Agent 以主动长轮询领取短期任务（调度端不主动连接 Runner）；
 * - 生产（Task 2/8）入口仅限 JUDGE 区 mTLS；dev/test 以代理密钥认证；
 * - 任务载荷只含代码、语言运行时标识与用例引用，不含数据区凭据；
 * - 测试数据仅按当前任务逐用例加密下发。
 */
@RestController
@RequestMapping("/api/judge/v1")
public class JudgeGatewayController {

    private final JudgeTaskService taskService;
    private final TestcaseDistributionService distributionService;
    private final JudgeResultGatewayService resultGatewayService;
    private final JudgeAgentService agentService;
    private final Clock clock;
    private final int maxWaitSeconds;
    private final boolean registrationEnabled;
    private final String internalToken;

    public JudgeGatewayController(JudgeTaskService taskService,
                                  TestcaseDistributionService distributionService,
                                  JudgeResultGatewayService resultGatewayService,
                                  JudgeAgentService agentService,
                                  Clock clock,
                                  @Value("${oj.judge.claim-max-wait-seconds:25}") int maxWaitSeconds,
                                  @Value("${oj.dev-internal-api.enabled:false}") boolean registrationEnabled,
                                  @Value("${oj.dev-internal-api.token:}") String internalToken) {
        this.taskService = taskService;
        this.distributionService = distributionService;
        this.resultGatewayService = resultGatewayService;
        this.agentService = agentService;
        this.clock = clock;
        this.maxWaitSeconds = maxWaitSeconds;
        this.registrationEnabled = registrationEnabled;
        this.internalToken = internalToken;
    }

    public record RegisterRequest(String agentId, String displayName) {
    }

    public record ClaimRequest(Integer waitSeconds) {
    }

    public record ResultRequest(String resultCode, java.math.BigDecimal normalizedScore,
                                long totalTimeMs, long peakMemoryKb, Integer resultVersion,
                                Integer snapshotVersion, String sandboxMode,
                                List<JudgeResultGatewayService.GatewayTestcaseOutcome> testcases,
                                String signature) {
    }

    private void requireAgentCredentials(String agentId, String agentToken) {
        if (agentId == null || agentId.isBlank() || agentToken == null || agentToken.isBlank()) {
            throw new ApiException(ErrorCode.AGENT_UNAUTHORIZED);
        }
    }

    /** dev/test 联调注册：生产由 mTLS 身份与部署注入替代（接口随内部 API 一起关闭）。 */
    @PostMapping("/agents/register")
    public Map<String, Object> register(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                        @RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ApiException(ErrorCode.FORBIDDEN, "代理注册接口未启用");
        }
        if (token == null || !oj.shared.GcmCrypto.constantTimeEquals(token, internalToken)) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        if (request.agentId() == null || request.agentId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少 agentId");
        }
        JudgeAgentService.RegisteredAgent registered =
                agentService.register(request.agentId(), request.displayName());
        return Map.of("agentId", registered.agentId(), "secret", registered.secret());
    }

    /** 主动长轮询领取任务：无可领取任务时阻塞至 waitSeconds 上限，返回 204。 */
    @PostMapping("/tasks/claim")
    public ResponseEntity<JudgeTaskService.TaskPayload> claim(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken,
            @RequestBody(required = false) ClaimRequest request) {
        requireAgentCredentials(agentId, agentToken);
        agentService.authenticate(agentId, agentToken);
        int wait = Math.min(request == null || request.waitSeconds() == null ? 0 : request.waitSeconds(),
                maxWaitSeconds);
        LocalDateTime deadline = LocalDateTime.now(clock).plusSeconds(wait);
        while (true) {
            JudgeTaskService.TaskPayload payload = taskService.claimOnce(agentId);
            if (payload != null) {
                return ResponseEntity.ok(payload);
            }
            if (!LocalDateTime.now(clock).isBefore(deadline)) {
                return ResponseEntity.noContent().build();
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.noContent().build();
            }
        }
    }

    /** 按当前任务逐用例拉取加密测试数据（无批量/全量接口）。 */
    @PostMapping("/tasks/{taskUuid}/testcases/{order}")
    public TestcaseDistributionService.DistributedTestcase testcase(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken,
            @PathVariable String taskUuid,
            @PathVariable int order) {
        requireAgentCredentials(agentId, agentToken);
        agentService.authenticate(agentId, agentToken);
        return distributionService.distribute(agentId, taskUuid, order);
    }

    /** 结果回传：签名 + 快照版本 + 资源上限 + 幂等校验后落库。 */
    @PostMapping("/tasks/{taskUuid}/result")
    public ResponseEntity<Map<String, Object>> result(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken,
            @PathVariable String taskUuid,
            @RequestBody ResultRequest request) {
        requireAgentCredentials(agentId, agentToken);
        agentService.authenticate(agentId, agentToken);
        var outcome = resultGatewayService.record(new JudgeResultGatewayService.GatewayResultCommand(
                taskUuid, agentId, request.resultCode(), request.normalizedScore(),
                request.totalTimeMs(), request.peakMemoryKb(),
                request.resultVersion() == null ? 1 : request.resultVersion(),
                request.snapshotVersion(), request.sandboxMode(), request.testcases(), request.signature()));
        HttpStatus status = outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of(
                "judgeResultId", outcome.result().getId(),
                "resultCode", outcome.result().getResultCode(),
                "resultVersion", outcome.result().getResultVersion(),
                "duplicate", outcome.duplicate()));
    }
}
