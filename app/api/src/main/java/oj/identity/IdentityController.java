package oj.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教务网页登录适配器对外接口（设计 5.3）。
 *
 * <p>刷新令牌通过 HttpOnly/Secure/SameSite Cookie 传递；Access Token 10 分钟。
 * 代提交接口的密码以 char[] 接收，仅存在于请求内存中，使用后立即清零。</p>
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    public static final String REFRESH_COOKIE = "oj_refresh_token";

    private final IdentityService identityService;
    private final AuditService auditService;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final long refreshTokenTtlHours;

    public IdentityController(IdentityService identityService,
                              AuditService auditService,
                              @Value("${oj.identity.cookie-secure:true}") boolean cookieSecure,
                              @Value("${oj.identity.cookie-same-site:Lax}") String cookieSameSite,
                              @Value("${oj.identity.refresh-token-ttl-hours:12}") long refreshTokenTtlHours) {
        this.identityService = identityService;
        this.auditService = auditService;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.refreshTokenTtlHours = refreshTokenTtlHours;
    }

    // ---------------- 登录发起与回调 ----------------

    @GetMapping("/login/start")
    public IdentityService.LoginStart start(@RequestParam(required = false) String callbackUrl,
                                            HttpServletRequest request) {
        return identityService.startLogin(callbackUrl, request.getRemoteAddr());
    }

    public record CallbackRequest(@NotBlank String state, @NotBlank String ticket,
                                  @NotBlank String callbackUrl, String totpCode) {
    }

    @PostMapping("/login/callback")
    public ResponseEntity<Map<String, Object>> callback(@Valid @RequestBody CallbackRequest body,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                body.state(), body.ticket(), body.callbackUrl(), request.getRemoteAddr(), body.totpCode());
        return toLoginResponse(outcome, response);
    }

    public record ProxyLoginRequest(@NotBlank String state, @NotBlank String externalNo,
                                    char[] password, @NotBlank String callbackUrl,
                                    String totpCode) {
    }

    /** 服务端代提交（生产登记模式）：密码不落库、不进日志，请求结束后清零。 */
    @PostMapping("/login/proxy-submitted")
    public ResponseEntity<Map<String, Object>> proxyLogin(@Valid @RequestBody ProxyLoginRequest body,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        try {
            IdentityService.LoginOutcome outcome = identityService.completeProxyLogin(
                    body.state(), body.externalNo(), body.password(), body.callbackUrl(),
                    request.getRemoteAddr(), body.totpCode());
            return toLoginResponse(outcome, response);
        } finally {
            java.util.Arrays.fill(body.password(), '\0');
        }
    }

    // ---------------- 刷新与登出 ----------------

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshValue,
                                       HttpServletResponse response) {
        IdentityService.SessionTokens tokens = identityService.refresh(refreshValue);
        addRefreshCookie(response, tokens.refreshToken());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", tokens.accessToken());
        body.put("expiresInMinutes", tokens.accessTokenTtlMinutes());
        return body;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshValue,
                                      HttpServletResponse response) {
        String access = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        identityService.logout(access, refreshValue);
        clearRefreshCookie(response);
        auditService.record(AuditActions.LOGOUT, "APP_USER", "self", null, null);
        return Map.of("ok", true);
    }

    // ---------------- 当前身份 ----------------

    @GetMapping("/me")
    public IdentityService.IdentityProfile me() {
        // AuthInterceptor 已解析并校验令牌
        var user = oj.auth.CurrentUserContext.get();
        if (user == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return identityService.me(user.appUserId());
    }

    // ---------------- 管理员 TOTP 绑定（一次性令牌驱动） ----------------

    public record EnrollRequest(@NotBlank String enrollToken) {
    }

    @PostMapping("/totp/enroll")
    public TotpService.Enrollment totpEnroll(@Valid @RequestBody EnrollRequest body) {
        return identityService.startTotpEnrollment(body.enrollToken());
    }

    public record ConfirmRequest(@NotBlank String enrollToken, @NotBlank String code) {
    }

    @PostMapping("/totp/confirm")
    public Map<String, Object> totpConfirm(@Valid @RequestBody ConfirmRequest body) {
        identityService.confirmTotpEnrollment(body.enrollToken(), body.code());
        return Map.of("ok", true);
    }

    // ---------------- 响应组装 ----------------

    private ResponseEntity<Map<String, Object>> toLoginResponse(IdentityService.LoginOutcome outcome,
                                                                HttpServletResponse response) {
        if (outcome instanceof IdentityService.LoginOutcome.MfaEnrollmentRequired mfa) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", ErrorCode.MFA_ENROLLMENT_REQUIRED.code());
            body.put("message", ErrorCode.MFA_ENROLLMENT_REQUIRED.defaultMessage());
            body.put("enrollToken", mfa.enrollToken());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        IdentityService.LoginOutcome.Success success = (IdentityService.LoginOutcome.Success) outcome;
        addRefreshCookie(response, success.tokens().refreshToken());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", success.tokens().accessToken());
        body.put("expiresInMinutes", success.tokens().accessTokenTtlMinutes());
        body.put("appUserId", success.appUserId());
        body.put("loginName", success.loginName());
        body.put("role", success.role());
        body.put("externalNo", success.externalNo());
        return ResponseEntity.ok(body);
    }

    private void addRefreshCookie(HttpServletResponse response, String refreshValue) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, refreshValue);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge((int) (refreshTokenTtlHours * 3600));
        response.addCookie(cookie);
        // Servlet Cookie 暂不支持 SameSite 属性，经 Set-Cookie 头补充
        String header = String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=%s%s",
                REFRESH_COOKIE, refreshValue, refreshTokenTtlHours * 3600, cookieSameSite,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
