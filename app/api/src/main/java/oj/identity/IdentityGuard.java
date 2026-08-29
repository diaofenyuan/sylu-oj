package oj.identity;

import oj.audit.AuditService;
import oj.auth.AppUser;
import oj.auth.AppUserRepository;
import oj.auth.AuthTokenRepository;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全关键状态的独立事务写入器（REQUIRES_NEW）。
 *
 * <p>登录链路在检测到异常时必须拒绝请求并回滚业务事务，但熔断、身份变更标记、
 * 失败计数/锁定、票据与 state 消费等安全状态必须独立提交——否则回滚会把
 * "拒绝的依据"一并抹掉，导致防重放与熔断在生产中失效。</p>
 */
@Service
public class IdentityGuard {

    private final IdentityAdapterStatusRepository adapterStatusRepository;
    private final AdapterPageFingerprintRepository fingerprintRepository;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final AppUserRepository appUserRepository;
    private final LoginStateRepository loginStateRepository;
    private final ConsumedTicketRepository consumedTicketRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuditService auditService;

    public IdentityGuard(IdentityAdapterStatusRepository adapterStatusRepository,
                         AdapterPageFingerprintRepository fingerprintRepository,
                         ExternalIdentityRepository externalIdentityRepository,
                         AppUserRepository appUserRepository,
                         LoginStateRepository loginStateRepository,
                         ConsumedTicketRepository consumedTicketRepository,
                         RefreshTokenRepository refreshTokenRepository,
                         AuthTokenRepository authTokenRepository,
                         AuditService auditService) {
        this.adapterStatusRepository = adapterStatusRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.appUserRepository = appUserRepository;
        this.loginStateRepository = loginStateRepository;
        this.consumedTicketRepository = consumedTicketRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authTokenRepository = authTokenRepository;
        this.auditService = auditService;
    }

    /** 熔断适配器：状态、指纹记录与审计独立提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void haltAdapter(String reason, String pageUrl, String changedFingerprint, LocalDateTime now) {
        if (changedFingerprint != null) {
            fingerprintRepository.save(new AdapterPageFingerprint(pageUrl, changedFingerprint,
                    AdapterPageFingerprint.Status.CHANGED, now));
        }
        IdentityAdapterStatus status = adapterStatusRepository.findById(IdentityAdapterStatus.SINGLETON_ID)
                .orElseGet(() -> adapterStatusRepository.save(new IdentityAdapterStatus(now)));
        status.halt(reason, now);
        String auditAction = "CAPTCHA".equals(reason)
                ? AuditActions.ADAPTER_CAPTCHA_DETECTED : AuditActions.ADAPTER_PAGE_CHANGED;
        auditService.record(auditAction, "ADAPTER", "1", null, java.util.Map.of("reason", reason));
        auditService.record(AuditActions.ADAPTER_HALTED, "ADAPTER", "1", null,
                java.util.Map.of("reason", reason));
    }

    /** 原子消费一次性 state：即使后续校验失败回滚，state 也保持已消费。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consumeState(Long stateId, LocalDateTime now) {
        return loginStateRepository.consumeIfAvailable(stateId, now) == 1;
    }

    /** 登记已消费票据：即使后续绑定失败回滚，票据仍不可重用。重复返回 false。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consumeTicket(String ticketHash, LocalDateTime now, LocalDateTime retentionUntil) {
        if (consumedTicketRepository.existsByTicketHash(ticketHash)) {
            return false;
        }
        consumedTicketRepository.save(new ConsumedTicket(ticketHash, now, retentionUntil));
        consumedTicketRepository.flush();
        return true;
    }

    /** 学号复用/身份变更：待确认标记独立提交，随后拒绝登录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIdentityChange(Long identityId, String newFingerprint) {
        ExternalIdentity identity = externalIdentityRepository.findById(identityId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        identity.markPendingConfirmation(newFingerprint);
        auditService.record(AuditActions.IDENTITY_CHANGE_DETECTED, "EXTERNAL_IDENTITY",
                String.valueOf(identityId), null, java.util.Map.of("externalNo", identity.getExternalNo()));
    }

    /** 教务侧账号停用：本地身份与账号停用独立提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disableIdentityByIdp(Long identityId) {
        ExternalIdentity identity = externalIdentityRepository.findById(identityId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        identity.disable();
        appUserRepository.findById(identity.getAppUserId()).ifPresent(AppUser::disable);
        auditService.record(AuditActions.IDENTITY_DISABLED, "EXTERNAL_IDENTITY",
                String.valueOf(identityId), null, "IDP_INACTIVE");
    }

    /** 登录失败累计与锁定：独立提交，防止回滚抹掉失败计数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(Long identityId, LocalDateTime now) {
        ExternalIdentity identity = externalIdentityRepository.findById(identityId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        identity.recordLoginFailure(now);
        if (identity.isLocked(now)) {
            auditService.record(AuditActions.LOGIN_LOCKED, "EXTERNAL_IDENTITY",
                    String.valueOf(identityId), null, null);
        }
    }

    /** 被锁定身份的登录尝试审计（独立提交）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditLockedAttempt(Long identityId) {
        auditService.record(AuditActions.LOGIN_LOCKED, "EXTERNAL_IDENTITY",
                String.valueOf(identityId), null, "ATTEMPT_WHILE_LOCKED");
    }

    /** 拒绝类审计（票据无效、未建档、关系缺失、教务不可用等），独立提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditLoginRejected(String targetType, String targetId, String reason) {
        auditService.record(AuditActions.IDENTITY_LOGIN_FAILED, targetType, targetId, null, reason);
    }

    /** 重放拒绝审计（一次性 state / 票据），独立提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditReplayRejected(String action, String targetType, String targetId) {
        auditService.record(action, targetType, targetId, null, null);
    }

    /** 刷新令牌失窃：整条轮换链与该用户访问令牌撤销，独立提交并审计。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeRefreshFamily(String familyId, Long appUserId, LocalDateTime now) {
        refreshTokenRepository.findByFamilyId(familyId).forEach(t -> t.revoke(now));
        appUserTokens(appUserId).forEach(oj.auth.AuthToken::revoke);
        auditService.record(AuditActions.REFRESH_REUSE_DETECTED, "APP_USER",
                String.valueOf(appUserId), null, java.util.Map.of("familyId", familyId));
    }

    private List<oj.auth.AuthToken> appUserTokens(Long appUserId) {
        return authTokenRepository.findByAppUserIdAndRevokedAtIsNull(appUserId);
    }
}
