package oj.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 认证拦截器：Bearer 令牌解析；/internal/** 使用独立的内部令牌。
 * 教务适配器登录入口与刷新/绑定端点匿名（见 oj.identity，一次性令牌或 Cookie 校验）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> ANONYMOUS_PATHS = Set.of(
            "/api/identity/login/start",
            "/api/identity/login/callback",
            "/api/identity/login/proxy-submitted",
            "/api/identity/refresh",
            "/api/identity/totp/enroll",
            "/api/identity/totp/confirm");

    private final LocalAccountService localAccountService;
    private final boolean internalApiEnabled;
    private final String internalApiToken;

    public AuthInterceptor(LocalAccountService localAccountService,
                           @Value("${oj.dev-internal-api.enabled:false}") boolean internalApiEnabled,
                           @Value("${oj.dev-internal-api.token:}") String internalApiToken) {
        this.localAccountService = localAccountService;
        this.internalApiEnabled = internalApiEnabled;
        this.internalApiToken = internalApiToken;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String path = request.getRequestURI();
        // 内部判题结果联调接口（dev）：独立令牌，不进入用户会话上下文
        if (path.startsWith("/internal/")) {
            if (!internalApiEnabled) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            String provided = request.getHeader("X-Internal-Token");
            if (provided == null || !constantTimeEquals(provided, internalApiToken)) {
                throw new ApiException(ErrorCode.AUTH_REQUIRED);
            }
            return true;
        }
        // 登录接口匿名
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/api/auth/login")) {
            return true;
        }
        if (ANONYMOUS_PATHS.contains(path)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        CurrentUser user = localAccountService.resolve(header.substring(7).trim());
        CurrentUserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        CurrentUserContext.clear();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
