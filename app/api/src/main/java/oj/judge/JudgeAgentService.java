package oj.judge;

import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.shared.GcmCrypto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 判题 Agent 注册、认证、HMAC 结果签名校验与熔断暂停。
 * 请求认证：X-Agent-Id + X-Agent-Token（注册时签发的代理密钥，密文落库）；
 * 结果签名：HMAC-SHA256（密钥与 Agent 身份绑定），防伪造与事后追责。
 */
@Service
public class JudgeAgentService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record RegisteredAgent(String agentId, String secret) {
    }

    private final JudgeAgentRepository repository;
    private final AgentSecretCipher secretCipher;
    private final JudgeGuard guard;
    private final AuditService auditService;
    private final Clock clock;

    public JudgeAgentService(JudgeAgentRepository repository,
                             AgentSecretCipher secretCipher,
                             JudgeGuard guard,
                             AuditService auditService,
                             Clock clock) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.guard = guard;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 注册（dev/test 联调）：代理已存在时返回既有密钥（幂等）。
     * 生产由 mTLS 客户端证书 CN 提供身份，密钥经部署注入后人工建档。
     */
    @Transactional
    public RegisteredAgent register(String agentId, String displayName) {
        JudgeAgent existing = repository.findById(agentId).orElse(null);
        if (existing != null) {
            return new RegisteredAgent(existing.getId(), secretCipher.decrypt(existing.getSecretEncrypted()));
        }
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = HexFormat.of().formatHex(secretBytes);
        repository.save(new JudgeAgent(agentId, displayName == null ? agentId : displayName,
                secretCipher.encrypt(secret)));
        auditService.record(AuditActions.JUDGE_AGENT_REGISTERED, "JUDGE_AGENT", agentId,
                null, Map.of("displayName", displayName == null ? agentId : displayName));
        return new RegisteredAgent(agentId, secret);
    }

    /** 请求级认证：未知代理/密钥不符 → 401；已熔断 → 403 并拒绝一切操作。 */
    public JudgeAgent authenticate(String agentId, String token) {
        JudgeAgent agent = repository.findById(agentId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_UNAUTHORIZED));
        if (JudgeAgent.SUSPENDED.equals(agent.getStatus())) {
            throw new ApiException(ErrorCode.AGENT_SUSPENDED);
        }
        String secret = secretCipher.decrypt(agent.getSecretEncrypted());
        if (!GcmCrypto.constantTimeEquals(secret, token == null ? "" : token)) {
            throw new ApiException(ErrorCode.AGENT_UNAUTHORIZED);
        }
        agent.markSeen(LocalDateTime.now(clock));
        repository.save(agent);
        return agent;
    }

    public String secretOf(JudgeAgent agent) {
        return secretCipher.decrypt(agent.getSecretEncrypted());
    }

    /** HMAC-SHA256 结果签名校验（常量时间比较）。 */
    public boolean verifyResultSignature(JudgeAgent agent, String canonical, String signatureHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretOf(agent).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return GcmCrypto.constantTimeEquals(expected, signatureHex == null ? "" : signatureHex);
        } catch (Exception e) {
            return false;
        }
    }

    public JudgeAgent requireAgent(String agentId) {
        return repository.findById(agentId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_UNAUTHORIZED));
    }

    /** 熔断暂停：拒绝路径写入，必须独立事务提交（外层事务随后回滚）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suspend(String agentId, String reason) {
        guard.suspendAgent(agentId, reason);
    }
}
