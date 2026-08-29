package oj.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import oj.audit.AuditService;
import oj.shared.AuditActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（本地合成账号，仅开发/内测）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LocalAccountService localAccountService;
    private final AuditService auditService;

    public AuthController(LocalAccountService localAccountService, AuditService auditService) {
        this.localAccountService = localAccountService;
        this.auditService = auditService;
    }

    public record LoginRequest(@NotBlank String loginName, @NotBlank String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        String token = localAccountService.login(request.loginName(), request.password());
        return Map.of(
                "token", token,
                "expiresInMinutes", localAccountService.tokenTtlMinutes());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            localAccountService.logout(authorization.substring(7).trim());
        }
        auditService.record(AuditActions.LOGOUT, "APP_USER", "self", null, null);
        return Map.of("ok", true);
    }
}
