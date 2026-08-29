package oj.identity;

import oj.audit.AuditService;
import oj.auth.AppUser;
import oj.auth.AppUserRepository;
import oj.auth.AuthToken;
import oj.auth.AuthTokenRepository;
import oj.classroom.StudentEnrollmentRepository;
import oj.classroom.StudentRepository;
import oj.classroom.TeacherAssignmentRepository;
import oj.classroom.TeacherRepository;
import oj.classroom.TeachingClass;
import oj.classroom.TeachingClassRepository;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task 5 核心：教务网页登录适配器的登录编排、身份绑定、令牌轮换与熔断管理。
 *
 * <p>安全不变量：</p>
 * <ul>
 *   <li>生产教师/学生只能经教务适配器登录；教务不可用时拒绝新登录，无任何本地降级；</li>
 *   <li>一次性 state 绑定来源 IP 与精确回调；票据仅用一次，重放被拒并审计；</li>
 *   <li>页面指纹变化或验证码出现立即熔断适配器并告警；</li>
 *   <li>学号/工号为唯一外部键，断言指纹变化进入人工确认而非静默继承；</li>
 *   <li>Access Token 10 分钟；刷新令牌单次轮换，重用触发整链撤销。</li>
 * </ul>
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    /** 教务账号专用：随机不可用密码哈希，保证本地密码路径无法命中（非预置密码）。 */
    private static final String UNUSABLE_PASSWORD_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .encode(java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID());

    private final EducationIdentityProvider provider;
    private final LoginStateRepository loginStateRepository;
    private final ConsumedTicketRepository consumedTicketRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final AdapterPageFingerprintRepository fingerprintRepository;
    private final IdentityAdapterStatusRepository adapterStatusRepository;
    private final TotpEnrollmentRepository totpEnrollmentRepository;
    private final AppUserRepository appUserRepository;
    private final AuthTokenRepository authTokenRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TeacherAssignmentRepository teacherAssignmentRepository;
    private final StudentEnrollmentRepository studentEnrollmentRepository;
    private final TeachingClassRepository teachingClassRepository;
    private final TotpService totpService;
    private final AuditService auditService;
    private final IdentityGuard identityGuard;
    private final LoginThrottle loginThrottle;
    private final Clock clock;

    private final boolean educationEnabled;
    private final long stateTtlMinutes;
    private final long accessTokenTtlMinutes;
    private final long refreshTokenTtlHours;
    private final long ticketRetentionMinutes;
    private final long enrollmentTtlMinutes;
    private final int maxConcurrentSessions;
    private final Set<String> callbackWhitelist;
    private final Set<String> adminStaffNumbers;

    public IdentityService(EducationIdentityProvider provider,
                           LoginStateRepository loginStateRepository,
                           ConsumedTicketRepository consumedTicketRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           ExternalIdentityRepository externalIdentityRepository,
                           AdapterPageFingerprintRepository fingerprintRepository,
                           IdentityAdapterStatusRepository adapterStatusRepository,
                           TotpEnrollmentRepository totpEnrollmentRepository,
                           AppUserRepository appUserRepository,
                           AuthTokenRepository authTokenRepository,
                           TeacherRepository teacherRepository,
                           StudentRepository studentRepository,
                           TeacherAssignmentRepository teacherAssignmentRepository,
                           StudentEnrollmentRepository studentEnrollmentRepository,
                           TeachingClassRepository teachingClassRepository,
                           TotpService totpService,
                           AuditService auditService,
                           IdentityGuard identityGuard,
                           LoginThrottle loginThrottle,
                           Clock clock,
                           @Value("${oj.identity.education.enabled:false}") boolean educationEnabled,
                           @Value("${oj.identity.state-ttl-minutes:5}") long stateTtlMinutes,
                           @Value("${oj.identity.access-token-ttl-minutes:10}") long accessTokenTtlMinutes,
                           @Value("${oj.identity.refresh-token-ttl-hours:12}") long refreshTokenTtlHours,
                           @Value("${oj.identity.ticket-retention-minutes:60}") long ticketRetentionMinutes,
                           @Value("${oj.identity.enrollment-ttl-minutes:30}") long enrollmentTtlMinutes,
                           @Value("${oj.identity.max-concurrent-sessions:8}") int maxConcurrentSessions,
                           @Value("${oj.identity.callback-whitelist:}") String callbackWhitelist,
                           @Value("${oj.identity.education.admin-staff-numbers:}") String adminStaffNumbers) {
        this.provider = provider;
        this.loginStateRepository = loginStateRepository;
        this.consumedTicketRepository = consumedTicketRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.adapterStatusRepository = adapterStatusRepository;
        this.totpEnrollmentRepository = totpEnrollmentRepository;
        this.appUserRepository = appUserRepository;
        this.authTokenRepository = authTokenRepository;
        this.teacherRepository = teacherRepository;
        this.studentRepository = studentRepository;
        this.teacherAssignmentRepository = teacherAssignmentRepository;
        this.studentEnrollmentRepository = studentEnrollmentRepository;
        this.teachingClassRepository = teachingClassRepository;
        this.totpService = totpService;
        this.auditService = auditService;
        this.identityGuard = identityGuard;
        this.loginThrottle = loginThrottle;
        this.clock = clock;
        this.educationEnabled = educationEnabled;
        this.stateTtlMinutes = stateTtlMinutes;
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        this.refreshTokenTtlHours = refreshTokenTtlHours;
        this.ticketRetentionMinutes = ticketRetentionMinutes;
        this.enrollmentTtlMinutes = enrollmentTtlMinutes;
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.callbackWhitelist = splitToSet(callbackWhitelist);
        this.adminStaffNumbers = splitToSet(adminStaffNumbers);
    }

    // ============================== 登录发起 ==============================

    @Transactional
    public LoginStart startLogin(String callbackUrl, String sourceIp) {
        requireEducationEnabled();
        loginThrottle.requireAllowed(sourceIp);
        requireAdapterActive();
        requireIdpAvailable();
        verifyPageFingerprint();
        String callback = requireWhitelistedCallback(callbackUrl);

        LocalDateTime now = LocalDateTime.now(clock);
        String stateValue = Hashing.randomHex(32);
        loginStateRepository.save(new LoginState(Hashing.sha256(stateValue), callback,
                sourceIp == null ? "unknown" : sourceIp, now, now.plusMinutes(stateTtlMinutes)));
        auditService.record(AuditActions.IDENTITY_LOGIN_START, "LOGIN_STATE", callback, null, null);
        return new LoginStart(stateValue, provider.loginPageUrl(), callback,
                stateTtlMinutes * 60, provider.supportsProxySubmission());
    }

    // ============================== 票据回调（跳转流程） ==============================

    @Transactional
    public LoginOutcome completeTicketLogin(String stateValue, String ticket, String callbackUrl,
                                            String sourceIp, String totpCode) {
        requireEducationEnabled();
        loginThrottle.requireAllowed(sourceIp);
        requireAdapterActive();
        requireIdpAvailable();
        verifyPageFingerprint();

        LoginState state = consumeState(stateValue, callbackUrl, sourceIp);
        ExternalAssertion assertion;
        try {
            if (consumedTicketRepository.existsByTicketHash(Hashing.sha256(ticket))) {
                identityGuard.auditReplayRejected(AuditActions.LOGIN_TICKET_REPLAY_REJECTED,
                        "LOGIN_STATE", String.valueOf(state.getId()));
                throw new ApiException(ErrorCode.TICKET_REPLAYED);
            }
            assertion = provider.verifyTicket(ticket);
            consumeTicket(ticket);
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.TICKET_INVALID) {
                loginThrottle.recordFailure(sourceIp);
                identityGuard.auditLoginRejected("LOGIN_STATE",
                        String.valueOf(state.getId()), "TICKET_INVALID");
            }
            throw e;
        }
        return loginWithAssertion(assertion, sourceIp, totpCode);
    }

    // ============================== 代提交流程（生产登记模式） ==============================

    @Transactional
    public LoginOutcome completeProxyLogin(String stateValue, String externalNo, char[] password,
                                           String callbackUrl, String sourceIp, String totpCode) {
        requireEducationEnabled();
        loginThrottle.requireAllowed(sourceIp);
        requireAdapterActive();
        requireIdpAvailable();
        verifyPageFingerprint();
        if (!provider.supportsProxySubmission()) {
            Arrays.fill(password, '\0');
            throw new ApiException(ErrorCode.ADAPTER_NOT_CERTIFIED);
        }
        consumeState(stateValue, callbackUrl, sourceIp);
        ExternalAssertion assertion;
        try {
            assertion = provider.proxyLogin(externalNo, password)
                    .orElseThrow(() -> new ApiException(ErrorCode.TICKET_INVALID));
        } catch (ApiException e) {
            loginThrottle.recordFailure(sourceIp);
            identityGuard.auditLoginRejected("EXTERNAL_NO", externalNo, "PROXY_LOGIN_FAILED");
            throw e;
        }
        return loginWithAssertion(assertion, sourceIp, totpCode);
    }

    // ============================== 共享登录路径 ==============================

    private LoginOutcome loginWithAssertion(ExternalAssertion assertion, String sourceIp, String totpCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        AppUser.Role role = resolveRole(assertion.externalNo());
        if (role == null) {
            identityGuard.auditLoginRejected("EXTERNAL_NO", assertion.externalNo(), "IDENTITY_UNRESOLVED");
            throw new ApiException(ErrorCode.IDENTITY_UNRESOLVED);
        }

        Long teacherId = role == AppUser.Role.TEACHER
                ? teacherRepository.findByStaffNo(assertion.externalNo()).orElseThrow().getId() : null;
        Long studentId = role == AppUser.Role.STUDENT
                ? studentRepository.findByStudentNo(assertion.externalNo()).orElseThrow().getId() : null;

        ExternalIdentity identity = externalIdentityRepository.findByExternalNo(assertion.externalNo())
                .orElse(null);
        AppUser user;
        if (identity == null) {
            // 教务账号无本地密码：写入随机不可用哈希，本地密码路径天然无法命中
            user = appUserRepository.save(new AppUser("edu-" + assertion.externalNo(),
                    UNUSABLE_PASSWORD_HASH, role, teacherId, studentId));
            ExternalIdentity.Type type = role == AppUser.Role.STUDENT
                    ? ExternalIdentity.Type.STUDENT : ExternalIdentity.Type.STAFF;
            identity = externalIdentityRepository.save(new ExternalIdentity(assertion.externalNo(),
                    type, user.getId(), assertion.fingerprint(), now));
            auditService.record(AuditActions.IDENTITY_BOUND, "EXTERNAL_IDENTITY",
                    String.valueOf(identity.getId()), null,
                    java.util.Map.of("externalNo", assertion.externalNo(), "role", role.name()));
        } else {
            user = appUserRepository.findById(identity.getAppUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
            if (identity.getStatus() == ExternalIdentity.Status.DISABLED) {
                throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
            }
            if (identity.getStatus() == ExternalIdentity.Status.PENDING_CONFIRMATION) {
                throw new ApiException(ErrorCode.IDENTITY_CONFIRMATION_REQUIRED);
            }
            if (!identity.getAssertionFingerprint().equals(assertion.fingerprint())) {
                identityGuard.markIdentityChange(identity.getId(), assertion.fingerprint());
                throw new ApiException(ErrorCode.IDENTITY_CONFIRMATION_REQUIRED);
            }
            if (!assertion.active()) {
                identityGuard.disableIdentityByIdp(identity.getId());
                throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
            }
            if (identity.isLocked(now)) {
                identityGuard.auditLockedAttempt(identity.getId());
                throw new ApiException(ErrorCode.LOGIN_LOCKED);
            }
        }

        if (user.getStatus() == AppUser.Status.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        enforceTeachingRelation(role, teacherId, studentId, identity);

        // 管理员第二因子
        if (role == AppUser.Role.ADMIN) {
            if (totpService.isConfirmed(user.getId())) {
                if (totpCode == null || totpCode.isBlank()) {
                    throw new ApiException(ErrorCode.MFA_REQUIRED);
                }
                if (!totpService.verifyLogin(user.getId(), totpCode)) {
                    identityGuard.recordLoginFailure(identity.getId(), now);
                    throw new ApiException(ErrorCode.MFA_FAILED);
                }
            } else {
                return LoginOutcome.mfaEnrollmentRequired(issueEnrollmentToken(user.getId()), user);
            }
        }

        identity.recordLoginSuccess(now);
        loginThrottle.recordSuccess(sourceIp);
        auditService.record(AuditActions.IDENTITY_LOGIN_SUCCESS, "EXTERNAL_IDENTITY",
                String.valueOf(identity.getId()), null, null);
        return LoginOutcome.success(issueSession(user, identity, now), user, identity);
    }

    private void enforceTeachingRelation(AppUser.Role role, Long teacherId, Long studentId,
                                         ExternalIdentity identity) {
        if (role == AppUser.Role.TEACHER) {
            boolean hasActive = !teacherAssignmentRepository
                    .findByTeacherIdAndActiveMarkerIsNotNullOrderByIdDesc(teacherId).isEmpty();
            if (!hasActive) {
                identityGuard.auditLoginRejected("EXTERNAL_IDENTITY",
                        String.valueOf(identity.getId()), "NO_TEACHING_RELATION");
                throw new ApiException(ErrorCode.NO_TEACHING_RELATION);
            }
        } else if (role == AppUser.Role.STUDENT) {
            boolean hasActive = studentEnrollmentRepository
                    .findByStudentIdAndActiveMarkerIsNotNull(studentId).isPresent();
            if (!hasActive) {
                identityGuard.auditLoginRejected("EXTERNAL_IDENTITY",
                        String.valueOf(identity.getId()), "NO_ACTIVE_ENROLLMENT");
                throw new ApiException(ErrorCode.NO_ACTIVE_ENROLLMENT);
            }
        }
    }

    private AppUser.Role resolveRole(String externalNo) {
        if (adminStaffNumbers.contains(externalNo)) {
            return AppUser.Role.ADMIN;
        }
        if (teacherRepository.findByStaffNo(externalNo).isPresent()) {
            return AppUser.Role.TEACHER;
        }
        if (studentRepository.findByStudentNo(externalNo).isPresent()) {
            return AppUser.Role.STUDENT;
        }
        return null;
    }

    private SessionTokens issueSession(AppUser user, ExternalIdentity identity, LocalDateTime now) {
        String accessValue = Hashing.randomHex(32);
        authTokenRepository.save(new AuthToken(Hashing.sha256(accessValue), user.getId(),
                now, now.plusMinutes(accessTokenTtlMinutes)));

        String refreshValue = Hashing.randomHex(32);
        refreshTokenRepository.save(new RefreshToken(Hashing.sha256(refreshValue), user.getId(),
                Hashing.randomHex(16), now, now.plusHours(refreshTokenTtlHours)));

        long activeSessions = refreshTokenRepository
                .countByAppUserIdAndRevokedAtIsNullAndConsumedAtIsNull(user.getId());
        if (activeSessions > maxConcurrentSessions) {
            auditService.record(AuditActions.SESSION_ANOMALY, "APP_USER", String.valueOf(user.getId()),
                    null, java.util.Map.of("activeSessions", activeSessions));
        }
        return new SessionTokens(accessValue, accessTokenTtlMinutes, refreshValue);
    }

    // ============================== 刷新与登出 ==============================

    @Transactional
    public SessionTokens refresh(String refreshValue) {
        requireEducationEnabled();
        if (refreshValue == null || refreshValue.isBlank()) {
            throw new ApiException(ErrorCode.REFRESH_INVALID);
        }
        RefreshToken token = refreshTokenRepository.findByTokenHash(Hashing.sha256(refreshValue))
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID));
        LocalDateTime now = LocalDateTime.now(clock);
        if (token.isRevoked() || token.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.REFRESH_INVALID);
        }
        if (token.isConsumed()) {
            // 令牌失窃征兆：独立事务撤销整条轮换链，不随本次拒绝回滚
            identityGuard.revokeRefreshFamily(token.getFamilyId(), token.getAppUserId(), now);
            throw new ApiException(ErrorCode.REFRESH_REUSED);
        }
        AppUser user = appUserRepository.findById(token.getAppUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID));
        if (user.getStatus() == AppUser.Status.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        externalIdentityRepository.findByAppUserId(user.getId()).ifPresent(identity -> {
            if (identity.getStatus() != ExternalIdentity.Status.ACTIVE) {
                throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
            }
        });

        token.consume(now);
        String accessValue = Hashing.randomHex(32);
        authTokenRepository.save(new AuthToken(Hashing.sha256(accessValue), user.getId(),
                now, now.plusMinutes(accessTokenTtlMinutes)));
        String newRefreshValue = Hashing.randomHex(32);
        refreshTokenRepository.save(new RefreshToken(Hashing.sha256(newRefreshValue), user.getId(),
                token.getFamilyId(), now, now.plusHours(refreshTokenTtlHours)));
        auditService.record(AuditActions.REFRESH_ROTATED, "APP_USER", String.valueOf(user.getId()),
                null, null);
        return new SessionTokens(accessValue, accessTokenTtlMinutes, newRefreshValue);
    }

    @Transactional
    public void logout(String accessValue, String refreshValue) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (accessValue != null && !accessValue.isBlank()) {
            authTokenRepository.findByTokenHash(Hashing.sha256(accessValue)).ifPresent(AuthToken::revoke);
        }
        if (refreshValue != null && !refreshValue.isBlank()) {
            refreshTokenRepository.findByTokenHash(Hashing.sha256(refreshValue))
                    .ifPresent(t -> {
                        t.revoke(now);
                        refreshTokenRepository.findByFamilyId(t.getFamilyId())
                                .forEach(sibling -> sibling.revoke(now));
                    });
        }
    }

    // ============================== 身份视图 ==============================

    @Transactional(readOnly = true)
    public IdentityProfile me(Long appUserId) {
        AppUser user = appUserRepository.findById(appUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
        ExternalIdentity identity = externalIdentityRepository.findByAppUserId(appUserId).orElse(null);
        List<IdentityProfile.TeachingRelation> relations = new ArrayList<>();
        IdentityProfile.EnrollmentInfo enrollment = null;
        if (user.getTeacherId() != null) {
            for (var ta : teacherAssignmentRepository
                    .findByTeacherIdAndActiveMarkerIsNotNullOrderByIdDesc(user.getTeacherId())) {
                TeachingClass tc = teachingClassRepository.findById(ta.getTeachingClassId()).orElse(null);
                relations.add(new IdentityProfile.TeachingRelation(ta.getTeachingClassId(),
                        tc == null ? null : tc.getName(), ta.getRole().name()));
            }
        }
        if (user.getStudentId() != null) {
            var se = studentEnrollmentRepository.findByStudentIdAndActiveMarkerIsNotNull(user.getStudentId())
                    .orElse(null);
            if (se != null) {
                TeachingClass tc = teachingClassRepository.findById(se.getTeachingClassId()).orElse(null);
                enrollment = new IdentityProfile.EnrollmentInfo(se.getTeachingClassId(),
                        tc == null ? null : tc.getName(), se.getTermId());
            }
        }
        return new IdentityProfile(user.getId(), user.getLoginName(), user.getRole().name(),
                identity == null ? null : identity.getExternalNo(), relations, enrollment);
    }

    // ============================== 管理员：身份告警与确认 ==============================

    @Transactional(readOnly = true)
    public List<ExternalIdentity> pendingConfirmations() {
        return externalIdentityRepository.findByStatus(ExternalIdentity.Status.PENDING_CONFIRMATION);
    }

    @Transactional
    public void confirmIdentity(Long identityId, boolean accept) {
        ExternalIdentity identity = externalIdentityRepository.findById(identityId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        if (accept) {
            identity.confirmActive(now);
            auditService.record(AuditActions.IDENTITY_CONFIRMED, "EXTERNAL_IDENTITY",
                    String.valueOf(identityId), null, null);
        } else {
            identity.disable();
            appUserRepository.findById(identity.getAppUserId()).ifPresent(AppUser::disable);
            revokeAllSessions(identity.getAppUserId());
            auditService.record(AuditActions.IDENTITY_DISABLED, "EXTERNAL_IDENTITY",
                    String.valueOf(identityId), null, "REJECTED_BY_ADMIN");
        }
    }

    // ============================== 管理员：适配器熔断与恢复 ==============================

    @Transactional(readOnly = true)
    public AdapterStatus adapterStatus() {
        IdentityAdapterStatus status = currentAdapterStatus();
        var fingerprints = fingerprintRepository.findAllByOrderByCapturedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 5));
        return new AdapterStatus(status.getStatus().name(), status.getHaltedReason(),
                status.getUpdatedAt(), provider.name(), provider.loginPageUrl(), fingerprints);
    }

    @Transactional
    public void resumeAdapter() {
        LocalDateTime now = LocalDateTime.now(clock);
        requireIdpAvailable();
        String fingerprint = provider.capturePageFingerprint();
        fingerprintRepository.save(new AdapterPageFingerprint(provider.loginPageUrl(), fingerprint,
                AdapterPageFingerprint.Status.OK, now));
        currentAdapterStatus().resume(now);
        auditService.record(AuditActions.ADAPTER_RESUMED, "ADAPTER", "1", null,
                java.util.Map.of("fingerprint", fingerprint));
    }

    // ============================== 管理员：批量撤销会话 ==============================

    @Transactional
    public void revokeAllSessions(Long appUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        authTokenRepository.findByAppUserIdAndRevokedAtIsNull(appUserId).forEach(AuthToken::revoke);
        refreshTokenRepository.findByAppUserIdAndRevokedAtIsNull(appUserId)
                .forEach(t -> t.revoke(now));
        auditService.record(AuditActions.SESSIONS_REVOKED, "APP_USER", String.valueOf(appUserId),
                null, null);
    }

    // ============================== TOTP 一次性绑定令牌 ==============================

    private String issueEnrollmentToken(Long appUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        String value = Hashing.randomHex(24);
        totpEnrollmentRepository.save(new TotpEnrollment(Hashing.sha256(value), appUserId,
                now.plusMinutes(enrollmentTtlMinutes)));
        return value;
    }

    @Transactional
    public TotpService.Enrollment startTotpEnrollment(String enrollmentToken) {
        TotpEnrollment enrollment = consumeEnrollmentToken(enrollmentToken, false);
        AppUser user = appUserRepository.findById(enrollment.getAppUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return totpService.startEnrollment(enrollment.getAppUserId(), user.getLoginName());
    }

    @Transactional
    public void confirmTotpEnrollment(String enrollmentToken, String code) {
        TotpEnrollment enrollment = consumeEnrollmentToken(enrollmentToken, true);
        totpService.confirmEnrollment(enrollment.getAppUserId(), code);
    }

    private TotpEnrollment consumeEnrollmentToken(String value, boolean consume) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.MFA_FAILED);
        }
        TotpEnrollment enrollment = totpEnrollmentRepository.findByTokenHash(Hashing.sha256(value))
                .orElseThrow(() -> new ApiException(ErrorCode.MFA_FAILED));
        LocalDateTime now = LocalDateTime.now(clock);
        if (enrollment.isConsumed() || enrollment.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.MFA_FAILED);
        }
        if (consume) {
            enrollment.consume(now);
        }
        return enrollment;
    }

    // ============================== 内部校验辅助 ==============================

    private LoginState consumeState(String stateValue, String callbackUrl, String sourceIp) {
        if (stateValue == null || stateValue.isBlank()) {
            throw new ApiException(ErrorCode.LOGIN_STATE_INVALID);
        }
        LoginState state = loginStateRepository.findByStateHash(Hashing.sha256(stateValue))
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_STATE_INVALID));
        LocalDateTime now = LocalDateTime.now(clock);
        if (state.getExpiresAt().isBefore(now) || state.isConsumed()) {
            identityGuard.auditReplayRejected(AuditActions.LOGIN_STATE_REPLAY_REJECTED,
                    "LOGIN_STATE", String.valueOf(state.getId()));
            throw new ApiException(ErrorCode.LOGIN_STATE_INVALID);
        }
        if (sourceIp != null && state.getSourceIp() != null
                && !state.getSourceIp().equals("unknown") && !state.getSourceIp().equals(sourceIp)) {
            throw new ApiException(ErrorCode.LOGIN_SOURCE_MISMATCH);
        }
        String callback = requireWhitelistedCallback(callbackUrl);
        if (!callback.equals(state.getCallbackUrl())) {
            throw new ApiException(ErrorCode.CALLBACK_NOT_ALLOWED);
        }
        // 独立事务原子消费：即使后续校验失败回滚，state 也不可再被使用
        if (!identityGuard.consumeState(state.getId(), now)) {
            identityGuard.auditReplayRejected(AuditActions.LOGIN_STATE_REPLAY_REJECTED,
                    "LOGIN_STATE", String.valueOf(state.getId()));
            throw new ApiException(ErrorCode.LOGIN_STATE_INVALID);
        }
        return state;
    }

    private void consumeTicket(String ticket) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 独立事务登记已消费票据：即使后续绑定失败回滚，票据也不可重用
        boolean consumed = identityGuard.consumeTicket(Hashing.sha256(ticket), now,
                now.plusMinutes(ticketRetentionMinutes));
        if (!consumed) {
            throw new ApiException(ErrorCode.TICKET_REPLAYED);
        }
        if (provider instanceof SimulatedEducationProvider simulated) {
            simulated.consumeTicket(ticket);
        }
    }

    private void verifyPageFingerprint() {
        String current;
        try {
            current = provider.capturePageFingerprint();
        } catch (AdapterHaltException e) {
            // 熔断状态独立提交，不随当前请求事务回滚
            identityGuard.haltAdapter(e.reason(), provider.loginPageUrl(), null,
                    LocalDateTime.now(clock));
            log.warn("教务登录适配器已熔断：reason={}", e.reason());
            throw new ApiException(ErrorCode.ADAPTER_HALTED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        var latest = fingerprintRepository.findAllByOrderByCapturedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            fingerprintRepository.save(new AdapterPageFingerprint(provider.loginPageUrl(), current,
                    AdapterPageFingerprint.Status.OK, now));
            return;
        }
        AdapterPageFingerprint baseline = latest.get(0);
        if (!baseline.getFingerprintHash().equals(current)) {
            identityGuard.haltAdapter("PAGE_CHANGED", provider.loginPageUrl(), current, now);
            log.warn("教务登录适配器已熔断：reason=PAGE_CHANGED");
            throw new ApiException(ErrorCode.ADAPTER_HALTED);
        }
    }

    private IdentityAdapterStatus currentAdapterStatus() {
        return adapterStatusRepository.findById(IdentityAdapterStatus.SINGLETON_ID)
                .orElseGet(() -> adapterStatusRepository.save(
                        new IdentityAdapterStatus(LocalDateTime.now(clock))));
    }

    private void requireAdapterActive() {
        if (currentAdapterStatus().isHalted()) {
            throw new ApiException(ErrorCode.ADAPTER_HALTED);
        }
    }

    private void requireIdpAvailable() {
        if (!provider.isAvailable()) {
            identityGuard.auditLoginRejected("ADAPTER", "1", "IDP_UNAVAILABLE");
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        }
    }

    private void requireEducationEnabled() {
        if (!educationEnabled) {
            throw new ApiException(ErrorCode.LOCAL_ACCOUNTS_DISABLED);
        }
    }

    private String requireWhitelistedCallback(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            if (callbackWhitelist.size() == 1) {
                return callbackWhitelist.iterator().next();
            }
            throw new ApiException(ErrorCode.CALLBACK_NOT_ALLOWED);
        }
        // 精确匹配，禁止通配或前缀匹配
        if (!callbackWhitelist.contains(callbackUrl)) {
            throw new ApiException(ErrorCode.CALLBACK_NOT_ALLOWED);
        }
        return callbackUrl;
    }

    private static Set<String> splitToSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                set.add(part.trim());
            }
        }
        return set;
    }

    // ============================== DTO ==============================

    public record LoginStart(String state, String loginPageUrl, String callbackUrl,
                             long expiresInSeconds, boolean proxySubmissionSupported) {
    }

    public record SessionTokens(String accessToken, long accessTokenTtlMinutes, String refreshToken) {
    }

    public record IdentityProfile(Long appUserId, String loginName, String role, String externalNo,
                                  List<TeachingRelation> teachingRelations, EnrollmentInfo enrollment) {
        public record TeachingRelation(Long teachingClassId, String className, String role) {
        }

        public record EnrollmentInfo(Long teachingClassId, String className, Long termId) {
        }
    }

    public record AdapterStatus(String status, String haltedReason, java.time.LocalDateTime updatedAt,
                                String provider, String loginPageUrl,
                                List<AdapterPageFingerprint> recentFingerprints) {
    }

    /** 登录结果：成功或需要管理员先完成 TOTP 绑定。 */
    public sealed interface LoginOutcome {

        static LoginOutcome success(SessionTokens tokens, AppUser user, ExternalIdentity identity) {
            return new Success(tokens, user.getId(), user.getLoginName(), user.getRole().name(),
                    identity.getExternalNo());
        }

        static LoginOutcome mfaEnrollmentRequired(String enrollToken, AppUser user) {
            return new MfaEnrollmentRequired(enrollToken, user.getId(), user.getLoginName());
        }

        record Success(SessionTokens tokens, Long appUserId, String loginName, String role,
                       String externalNo) implements LoginOutcome {
        }

        record MfaEnrollmentRequired(String enrollToken, Long appUserId, String loginName)
                implements LoginOutcome {
        }
    }
}
