package oj.identity;

import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录入口限流：以账号维度为主（见 ExternalIdentity 锁定），
 * IP 维度仅作辅助信号且阈值放宽（学生共用 NAT 出口），只封登录动作不拒全站服务。
 */
@Component
public class LoginThrottle {

    /** IP 维度：10 分钟内失败超过该次数才告警并暂停该来源的登录动作。 */
    private static final int IP_FAILURE_THRESHOLD = 50;
    private static final long WINDOW_SECONDS = 600;
    private static final long BLOCK_SECONDS = 900;

    private final AuditService auditService;
    private final Clock clock;
    private final Map<String, IpCounter> failures = new ConcurrentHashMap<>();

    public LoginThrottle(AuditService auditService, Clock clock) {
        this.auditService = auditService;
        this.clock = clock;
    }

    public void requireAllowed(String sourceIp) {
        if (sourceIp == null) {
            return;
        }
        IpCounter counter = failures.get(sourceIp);
        long now = clock.instant().getEpochSecond();
        if (counter != null && counter.blockedUntil > now) {
            throw new ApiException(ErrorCode.LOGIN_THROTTLED);
        }
    }

    public void recordFailure(String sourceIp) {
        if (sourceIp == null) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        IpCounter counter = failures.computeIfAbsent(sourceIp, ip -> new IpCounter());
        synchronized (counter) {
            if (now - counter.windowStart > WINDOW_SECONDS) {
                counter.windowStart = now;
                counter.count = 0;
            }
            counter.count++;
            if (counter.count >= IP_FAILURE_THRESHOLD) {
                counter.blockedUntil = now + BLOCK_SECONDS;
                counter.count = 0;
                auditService.record(AuditActions.LOGIN_THROTTLED, "SOURCE_IP", sourceIp, null, null);
            }
        }
    }

    public void recordSuccess(String sourceIp) {
        if (sourceIp != null) {
            failures.remove(sourceIp);
        }
    }

    private static final class IpCounter {
        long windowStart;
        int count;
        long blockedUntil;
    }
}
