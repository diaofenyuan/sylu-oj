package oj.auth;

import jakarta.annotation.PostConstruct;
import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 本地合成账号与令牌服务（仅开发/内测）。
 *
 * <p>安全边界：生产环境 oj.auth.local-accounts-enabled=false，
 * 登录与建号全部拒绝；Task 5 的教务网页登录适配器替代本服务。
 * 令牌仅保存 SHA-256 哈希；BCrypt 存储密码。</p>
 */
@Service
public class LocalAccountService {

    private static final Logger log = LoggerFactory.getLogger(LocalAccountService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final AppUserRepository appUserRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuditService auditService;
    private final boolean localAccountsEnabled;
    private final boolean educationExclusive;
    private final String bootstrapAdminLogin;
    private final String bootstrapAdminPassword;
    private final long tokenTtlMinutes;

    public LocalAccountService(AppUserRepository appUserRepository,
                               AuthTokenRepository authTokenRepository,
                               AuditService auditService,
                               @Value("${oj.auth.local-accounts-enabled:false}") boolean localAccountsEnabled,
                               @Value("${oj.identity.education.enforce-exclusive:false}") boolean educationExclusive,
                               @Value("${oj.auth.bootstrap-admin.login:}") String bootstrapAdminLogin,
                               @Value("${oj.auth.bootstrap-admin.password:}") String bootstrapAdminPassword,
                               @Value("${oj.auth.token-ttl-minutes:720}") long tokenTtlMinutes) {
        this.appUserRepository = appUserRepository;
        this.authTokenRepository = authTokenRepository;
        this.auditService = auditService;
        this.localAccountsEnabled = localAccountsEnabled;
        this.educationExclusive = educationExclusive;
        this.bootstrapAdminLogin = bootstrapAdminLogin;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    @PostConstruct
    @Transactional
    void bootstrapAdmin() {
        if (!localAccountsEnabled || bootstrapAdminLogin == null || bootstrapAdminLogin.isBlank()) {
            return;
        }
        if (appUserRepository.existsByLoginName(bootstrapAdminLogin)) {
            return;
        }
        String password = bootstrapAdminPassword;
        if (password == null || password.isBlank()) {
            password = randomToken();
            log.warn("开发环境引导管理员 [{}] 初始密码（仅打印一次，请自行保管）：{}", bootstrapAdminLogin, password);
        }
        appUserRepository.save(new AppUser(bootstrapAdminLogin, PASSWORD_ENCODER.encode(password),
                AppUser.Role.ADMIN, null, null));
    }

    @Transactional
    public String login(String loginName, String password) {
        requireLocalAccountsEnabled();
        AppUser user = appUserRepository.findByLoginName(loginName)
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_FAILED));
        if (!PASSWORD_ENCODER.matches(password == null ? "" : password, user.getPasswordHash())) {
            auditService.record(AuditActions.LOGIN_FAILED, "APP_USER", String.valueOf(user.getId()), null, null);
            throw new ApiException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == AppUser.Status.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        String token = randomToken();
        LocalDateTime now = LocalDateTime.now();
        authTokenRepository.save(new AuthToken(sha256(token), user.getId(), now, now.plusMinutes(tokenTtlMinutes)));
        auditService.record(AuditActions.LOGIN_SUCCESS, "APP_USER", String.valueOf(user.getId()), null, null);
        return token;
    }

    @Transactional
    public void logout(String token) {
        authTokenRepository.findByTokenHash(sha256(token)).ifPresent(AuthToken::revoke);
    }

    @Transactional(readOnly = true)
    public CurrentUser resolve(String token) {
        AuthToken authToken = authTokenRepository.findByTokenHash(sha256(token))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
        if (authToken.isRevoked() || authToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        AppUser user = appUserRepository.findById(authToken.getAppUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
        if (user.getStatus() == AppUser.Status.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        return new CurrentUser(user.getId(), user.getLoginName(), user.getRole(),
                user.getTeacherId(), user.getStudentId());
    }

    /**
     * 创建本地合成账号（仅开发/内测）。生产环境直接拒绝。
     */
    @Transactional
    public AppUser createLocalAccount(AppUser.Role role, String loginName, String password,
                                      Long teacherId, Long studentId) {
        requireLocalAccountsEnabled();
        if (appUserRepository.existsByLoginName(loginName)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        if (password == null || password.length() < 8) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "合成账号密码长度至少 8 位");
        }
        AppUser user = appUserRepository.save(new AppUser(loginName,
                PASSWORD_ENCODER.encode(password), role, teacherId, studentId));
        auditService.record(AuditActions.LOCAL_ACCOUNT_CREATED, "APP_USER", String.valueOf(user.getId()),
                null, java.util.Map.of("loginName", loginName, "role", role.name()));
        return user;
    }

    private void requireLocalAccountsEnabled() {
        // 教务适配器独占模式（生产）：禁止任何本地密码/合成账号降级路径
        if (educationExclusive || !localAccountsEnabled) {
            throw new ApiException(ErrorCode.LOCAL_ACCOUNTS_DISABLED);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public long tokenTtlMinutes() {
        return tokenTtlMinutes;
    }
}
