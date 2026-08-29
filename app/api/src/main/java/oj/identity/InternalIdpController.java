package oj.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部模拟教务控制端点（仅 dev/test profile，经 /internal/** 内部令牌保护）。
 * 用于自动化测试驱动模拟 IdP：签发合成票据、切换可用性、页面版本与验证码。
 * 生产环境内部接口整体禁用且不存在模拟提供方，本控制器不可用。
 */
@RestController
@RequestMapping("/internal/idp")
public class InternalIdpController {

    private final ObjectProvider<SimulatedEducationProvider> provider;
    private final AdminTotpRepository adminTotpRepository;
    private final TotpEnrollmentRepository totpEnrollmentRepository;

    public InternalIdpController(ObjectProvider<SimulatedEducationProvider> provider,
                                 AdminTotpRepository adminTotpRepository,
                                 TotpEnrollmentRepository totpEnrollmentRepository) {
        this.provider = provider;
        this.adminTotpRepository = adminTotpRepository;
        this.totpEnrollmentRepository = totpEnrollmentRepository;
    }

    public record IssueTicketRequest(@NotBlank String externalNo, String name,
                                     @NotBlank String type) {
    }

    @PostMapping("/issue-ticket")
    public Map<String, Object> issueTicket(@Valid @RequestBody IssueTicketRequest request) {
        ExternalAssertion.Type type = ExternalAssertion.Type.valueOf(request.type());
        String ticket = simulated().issueTicket(new ExternalAssertion(
                request.externalNo(), type, request.name(), true));
        return Map.of("ticket", ticket);
    }

    public record SetAvailableRequest(@NotNull Boolean available) {
    }

    @PostMapping("/set-available")
    public Map<String, Object> setAvailable(@Valid @RequestBody SetAvailableRequest request) {
        simulated().setAvailable(request.available());
        return Map.of("ok", true);
    }

    public record SetPageVersionRequest(@NotBlank String version) {
    }

    @PostMapping("/set-page-version")
    public Map<String, Object> setPageVersion(@Valid @RequestBody SetPageVersionRequest request) {
        simulated().setPageVersion(request.version());
        return Map.of("ok", true);
    }

    public record SetCaptchaRequest(@NotNull Boolean required) {
    }

    @PostMapping("/set-captcha-required")
    public Map<String, Object> setCaptchaRequired(@Valid @RequestBody SetCaptchaRequest request) {
        simulated().setCaptchaRequired(request.required());
        return Map.of("ok", true);
    }

    /** 重置管理员双因子状态，保证套件在持久化开发库上可重复运行。 */
    @PostMapping("/reset-admin-totp")
    public Map<String, Object> resetAdminTotp() {
        simulated();
        totpEnrollmentRepository.deleteAll();
        adminTotpRepository.deleteAll();
        return Map.of("ok", true);
    }

    private SimulatedEducationProvider simulated() {
        SimulatedEducationProvider sim = provider.getIfAvailable();
        if (sim == null) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return sim;
    }
}
