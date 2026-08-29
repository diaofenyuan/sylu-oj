package oj.identity;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟教务系统（仅开发/联调/自动化测试）。
 *
 * <p>只接受合成学号/工号与内部端点签发的一次性票据，绝不接触真实教务密码。
 * 可切换不可用、页面版本变化与验证码出现，用于验证熔断与无降级行为。</p>
 */
@Component
@ConditionalOnProperty(name = "oj.identity.education.provider", havingValue = "simulated")
public class SimulatedEducationProvider implements EducationIdentityProvider {

    public static final String LOGIN_PAGE = "https://jxw.sylu.edu.cn/xtgl/login_slogin.html";

    private final Map<String, ExternalAssertion> tickets = new ConcurrentHashMap<>();

    private volatile boolean available = true;
    private volatile String pageVersion = "v1";
    private volatile boolean captchaRequired = false;

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String loginPageUrl() {
        return LOGIN_PAGE;
    }

    @Override
    public String capturePageFingerprint() {
        if (!available) {
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        }
        if (captchaRequired) {
            throw new AdapterHaltException("CAPTCHA", "教务登录页出现验证码，停止自动认证");
        }
        return Hashing.sha256("sylu-jxw-simulated|" + pageVersion + "|fields[username,password,submit]");
    }

    @Override
    public ExternalAssertion verifyTicket(String ticket) {
        if (!available) {
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        }
        ExternalAssertion assertion = ticket == null ? null : tickets.get(ticket);
        if (assertion == null) {
            throw new ApiException(ErrorCode.TICKET_INVALID);
        }
        return assertion;
    }

    // ---------------- 仅供内部联调端点（/internal/idp/**）使用 ----------------

    public String issueTicket(ExternalAssertion assertion) {
        String ticket = Hashing.randomHex(24);
        tickets.put(ticket, assertion);
        return ticket;
    }

    public void consumeTicket(String ticket) {
        tickets.remove(ticket);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setPageVersion(String pageVersion) {
        this.pageVersion = pageVersion;
    }

    public void setCaptchaRequired(boolean captchaRequired) {
        this.captchaRequired = captchaRequired;
    }
}
