package oj.identity;

import oj.audit.AuditEventRepository;
import oj.auth.LocalAccountService;
import oj.classroom.ClassroomService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 5：教务网页登录适配器与会话安全。
 *
 * <p>自动化测试只使用模拟教务提供方与合成学号/工号，不接触任何真实教务密码；
 * 生产验收由账号持有人现场输入真实账号完成（见 docs/runbooks/education-login-adapter.md）。</p>
 *
 * <p>注意：本类刻意不使用 {@code @Transactional} 回滚——熔断、票据消费、身份变更等
 * 安全状态经 {@link IdentityGuard} 以独立事务提交，事务回滚型测试无法覆盖其真实性能。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class TeachingLoginTest extends oj.classroom.TestSupport {

    private static final String CALLBACK = "https://oj.test/api/identity/login/callback";
    private static final String ADMIN_NO = "ADM-0001";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private IdentityService identityService;
    @Autowired
    private SimulatedEducationProvider simulated;
    @Autowired
    private TotpService totpService;
    @Autowired
    private LocalAccountService localAccountService;
    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ExternalIdentityRepository externalIdentityRepository;
    @Autowired
    private LoginStateRepository loginStateRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    private Long classId;
    private String teacherNo;
    private String studentNo;

    @BeforeEach
    void setup() {
        asAdmin();
        cleanIdentityTables();
        // 模拟教务提供方复位（Bean 跨测试复用）
        simulated.setAvailable(true);
        simulated.setPageVersion("v1");
        simulated.setCaptchaRequired(false);

        long n = SEQ.incrementAndGet();
        teacherNo = "T-5001-" + n;
        studentNo = "S-5001-" + n;
        Long termId = classroomService.createTerm("T-IDENT-" + n, "身份学期",
                LocalDate.now().minusDays(5), LocalDate.now().plusMonths(4)).getId();
        Long courseId = classroomService.createCourse("CS-IDENT-" + n, "程序设计", BigDecimal.ONE).getId();
        classId = classroomService.createTeachingClass(termId, courseId, null, "ID-A-" + n, "身份教学班").getId();
        Long teacherId = classroomService.createTeacher(teacherNo, "主讲教师").getId();
        Long studentId = classroomService.createStudent(studentNo, "在册学生").getId();
        classroomService.assignTeacher(classId, teacherId, oj.classroom.TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);
    }

    /** 清理身份模块持久状态：测试间完全隔离（审计一并清空，断言只针对当前用例）。 */
    private void cleanIdentityTables() {
        jdbcTemplate.update("delete from refresh_token");
        jdbcTemplate.update("delete from totp_enrollment");
        jdbcTemplate.update("delete from admin_totp");
        jdbcTemplate.update("delete from external_identity");
        jdbcTemplate.update("delete from auth_token where app_user_id in "
                + "(select id from app_user where login_name like 'edu-%')");
        jdbcTemplate.update("delete from app_user where login_name like 'edu-%'");
        jdbcTemplate.update("delete from login_state");
        jdbcTemplate.update("delete from consumed_ticket");
        jdbcTemplate.update("delete from adapter_page_fingerprint");
        jdbcTemplate.update("update identity_adapter_status set status = 'ACTIVE', halted_reason = null");
        jdbcTemplate.update("delete from audit_event");
    }

    // ============================== 正常链路与身份绑定 ==============================

    @Test
    void teacherLoginBindsIdentityAndLoadsPrimaryRole() {
        IdentityService.LoginOutcome.Success result =
                loginSuccess(teacherNo, ExternalAssertion.Type.STAFF, "主讲教师", "10.5.0.1");
        assertThat(result.role()).isEqualTo("TEACHER");
        assertThat(result.externalNo()).isEqualTo(teacherNo);

        IdentityService.IdentityProfile profile = identityService.me(result.appUserId());
        assertThat(profile.externalNo()).isEqualTo(teacherNo);
        assertThat(profile.teachingRelations()).hasSize(1);
        assertThat(profile.teachingRelations().get(0).teachingClassId()).isEqualTo(classId);
        assertThat(profile.teachingRelations().get(0).role()).isEqualTo("PRIMARY");

        ExternalIdentity identity = externalIdentityRepository.findByExternalNo(teacherNo).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(ExternalIdentity.Status.ACTIVE);
        assertThat(identity.getLastLoginAt()).isNotNull();
        assertAuditContains(AuditActions.IDENTITY_BOUND);
        assertAuditContains(AuditActions.IDENTITY_LOGIN_SUCCESS);
    }

    @Test
    void studentLoginLoadsActiveEnrollment() {
        IdentityService.LoginOutcome.Success result =
                loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.2");
        assertThat(result.role()).isEqualTo("STUDENT");

        IdentityService.IdentityProfile profile = identityService.me(result.appUserId());
        assertThat(profile.enrollment()).isNotNull();
        assertThat(profile.enrollment().teachingClassId()).isEqualTo(classId);
        assertThat(profile.teachingRelations()).isEmpty();
    }

    @Test
    void unregisteredExternalNoRejected() {
        String ticket = simulated.issueTicket(new ExternalAssertion("S-9999",
                ExternalAssertion.Type.STUDENT, "未建档", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.3");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.3", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDENTITY_UNRESOLVED));
    }

    @Test
    void teacherWithoutActiveAssignmentRejected() {
        asAdmin();
        classroomService.createTeacher("T-5002", "无授课教师");
        String ticket = simulated.issueTicket(new ExternalAssertion("T-5002",
                ExternalAssertion.Type.STAFF, "无授课教师", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.4");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.4", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NO_TEACHING_RELATION));
    }

    @Test
    void studentWithoutEnrollmentRejected() {
        asAdmin();
        classroomService.createStudent("S-5002", "未选课学生");
        String ticket = simulated.issueTicket(new ExternalAssertion("S-5002",
                ExternalAssertion.Type.STUDENT, "未选课学生", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.5");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.5", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NO_ACTIVE_ENROLLMENT));
    }

    // ============================== state / 票据 / 回调防重放 ==============================

    @Test
    void stateIsOneTimeAndReplayRejected() {
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.6");
        String ticket1 = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start.state(), ticket1, CALLBACK, "10.5.0.6", null);
        assertThat(outcome).isInstanceOf(IdentityService.LoginOutcome.Success.class);

        // 同一 state 第二次使用（即使携带新票据）被拒并审计
        String ticket2 = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket2, CALLBACK, "10.5.0.6", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOGIN_STATE_INVALID));
        assertAuditContains(AuditActions.LOGIN_STATE_REPLAY_REJECTED);
    }

    @Test
    void expiredStateRejected() {
        LocalDateTime now = LocalDateTime.now(clock);
        String stateValue = Hashing.randomHex(32);
        loginStateRepository.save(new LoginState(Hashing.sha256(stateValue), CALLBACK,
                "10.5.0.7", now.minusMinutes(10), now.minusMinutes(5)));
        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                stateValue, ticket, CALLBACK, "10.5.0.7", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOGIN_STATE_INVALID));
    }

    @Test
    void ticketReplayRejected() {
        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        IdentityService.LoginStart start1 = identityService.startLogin(CALLBACK, "10.5.0.8");
        identityService.completeTicketLogin(start1.state(), ticket, CALLBACK, "10.5.0.8", null);

        // 同一票据第二次使用（新 state）被拒并审计
        IdentityService.LoginStart start2 = identityService.startLogin(CALLBACK, "10.5.0.8");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start2.state(), ticket, CALLBACK, "10.5.0.8", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TICKET_REPLAYED));
        assertAuditContains(AuditActions.LOGIN_TICKET_REPLAY_REJECTED);
    }

    @Test
    void sourceIpMismatchRejected() {
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.9");
        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.9.9.9", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOGIN_SOURCE_MISMATCH));
    }

    @Test
    void callbackWhitelistEnforcedByExactMatch() {
        assertThatThrownBy(() -> identityService.startLogin("https://evil.example/callback", "10.5.0.10"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CALLBACK_NOT_ALLOWED));
        // 前缀变体同样拒绝（禁止前缀匹配）
        assertThatThrownBy(() -> identityService.startLogin(CALLBACK + "/extra", "10.5.0.10"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CALLBACK_NOT_ALLOWED));
    }

    @Test
    void callbackUrlMustMatchStateBinding() {
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.11");
        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        IdentityService.LoginOutcome ok = identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.11", null);
        assertThat(ok).isInstanceOf(IdentityService.LoginOutcome.Success.class);
        // 另起 state 但回调地址不一致时拒绝
        IdentityService.LoginStart start2 = identityService.startLogin(null, "10.5.0.11");
        String ticket2 = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start2.state(), ticket2, "https://oj.test/other-callback", "10.5.0.11", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CALLBACK_NOT_ALLOWED));
    }

    // ============================== 身份变更与账号停用 ==============================

    @Test
    void identityChangeRequiresManualConfirmation() {
        loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "原姓名", "10.5.0.12");

        // 同学号不同姓名 → 判定身份变更，停止自动登录并告警
        String changedTicket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "新姓名", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.12");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), changedTicket, CALLBACK, "10.5.0.12", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDENTITY_CONFIRMATION_REQUIRED));
        assertAuditContains(AuditActions.IDENTITY_CHANGE_DETECTED);

        ExternalIdentity identity = externalIdentityRepository.findByExternalNo(studentNo).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(ExternalIdentity.Status.PENDING_CONFIRMATION);

        // 未确认前继续拒绝
        String retryTicket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "新姓名", true));
        IdentityService.LoginStart start2 = identityService.startLogin(CALLBACK, "10.5.0.12");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start2.state(), retryTicket, CALLBACK, "10.5.0.12", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDENTITY_CONFIRMATION_REQUIRED));

        // 管理员人工确认后恢复登录（新指纹已在变更检测时记录）
        asAdmin();
        identityService.confirmIdentity(identity.getId(), true);
        assertAuditContains(AuditActions.IDENTITY_CONFIRMED);

        String okTicket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "新姓名", true));
        IdentityService.LoginStart start3 = identityService.startLogin(CALLBACK, "10.5.0.12");
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start3.state(), okTicket, CALLBACK, "10.5.0.12", null);
        assertThat(outcome).isInstanceOf(IdentityService.LoginOutcome.Success.class);
    }

    @Test
    void rejectedIdentityConfirmationDisablesAccount() {
        loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "原姓名", "10.5.0.13");
        String changedTicket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "新姓名", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.13");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), changedTicket, CALLBACK, "10.5.0.13", null))
                .isInstanceOf(ApiException.class);

        ExternalIdentity identity = externalIdentityRepository.findByExternalNo(studentNo).orElseThrow();
        asAdmin();
        identityService.confirmIdentity(identity.getId(), false);

        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "新姓名", true));
        IdentityService.LoginStart start2 = identityService.startLogin(CALLBACK, "10.5.0.13");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start2.state(), ticket, CALLBACK, "10.5.0.13", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED));
    }

    @Test
    void idpAssertionInactiveDisablesLocalAccount() {
        loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.14");

        // 教务侧账号停用（毕业/离校）→ 本地会话与身份同步停用
        String offTicket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", false));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.14");
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), offTicket, CALLBACK, "10.5.0.14", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED));

        ExternalIdentity identity = externalIdentityRepository.findByExternalNo(studentNo).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(ExternalIdentity.Status.DISABLED);
    }

    // ============================== 教务不可用与无降级 ==============================

    @Test
    void idpUnavailableRejectsNewLogins() {
        simulated.setAvailable(false);
        assertThatThrownBy(() -> identityService.startLogin(CALLBACK, "10.5.0.15"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDP_UNAVAILABLE));

        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                "any-state", ticket, CALLBACK, "10.5.0.15", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDP_UNAVAILABLE));
    }

    // ============================== 页面指纹与验证码熔断 ==============================

    @Test
    void pageFingerprintChangeHaltsAdapterUntilReviewed() {
        loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.16");

        simulated.setPageVersion("v2");
        assertThatThrownBy(() -> identityService.startLogin(CALLBACK, "10.5.0.16"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADAPTER_HALTED));
        assertAuditContains(AuditActions.ADAPTER_PAGE_CHANGED);
        assertAuditContains(AuditActions.ADAPTER_HALTED);
        assertThat(identityService.adapterStatus().status()).isEqualTo("HALTED");

        // 熔断期间即使持有效票据也拒绝
        String ticket = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                "any-state", ticket, CALLBACK, "10.5.0.16", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADAPTER_HALTED));

        // 管理员人工核查后恢复，新版本指纹成为基线
        asAdmin();
        identityService.resumeAdapter();
        assertAuditContains(AuditActions.ADAPTER_RESUMED);
        assertThat(identityService.adapterStatus().status()).isEqualTo("ACTIVE");

        String ticket2 = simulated.issueTicket(new ExternalAssertion(studentNo,
                ExternalAssertion.Type.STUDENT, "在册学生", true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.16");
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start.state(), ticket2, CALLBACK, "10.5.0.16", null);
        assertThat(outcome).isInstanceOf(IdentityService.LoginOutcome.Success.class);
    }

    @Test
    void captchaDetectionHaltsAdapterWithoutBypass() {
        simulated.setCaptchaRequired(true);
        assertThatThrownBy(() -> identityService.startLogin(CALLBACK, "10.5.0.17"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADAPTER_HALTED));
        assertAuditContains(AuditActions.ADAPTER_CAPTCHA_DETECTED);
        assertThat(identityService.adapterStatus().haltedReason()).isEqualTo("CAPTCHA");
    }

    // ============================== 令牌生命周期 ==============================

    @Test
    void refreshRotationIsSingleUseAndReuseRevokesFamily() {
        IdentityService.LoginOutcome.Success result =
                loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.18");
        String refresh1 = result.tokens().refreshToken();

        IdentityService.SessionTokens rotated = identityService.refresh(refresh1);
        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(refresh1);
        assertAuditContains(AuditActions.REFRESH_ROTATED);

        // 旧刷新令牌重用 → 判定失窃，整链撤销
        assertThatThrownBy(() -> identityService.refresh(refresh1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_REUSED));
        assertAuditContains(AuditActions.REFRESH_REUSE_DETECTED);

        // 轮换链上的新令牌也随之失效
        assertThatThrownBy(() -> identityService.refresh(rotated.refreshToken()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_INVALID));
    }

    @Test
    void accessTokenExpiryEnforced() {
        IdentityService.LoginOutcome.Success result =
                loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.19");
        String token = result.tokens().accessToken();
        assertThat(result.tokens().accessTokenTtlMinutes()).isEqualTo(10);
        assertThat(localAccountService.resolve(token).appUserId()).isEqualTo(result.appUserId());

        // 到期后拒绝（模拟 10 分钟过期）
        jdbcTemplate.update("update auth_token set expires_at = ? where token_hash = ?",
                LocalDateTime.now(clock).minusMinutes(1), Hashing.sha256(token));
        assertThatThrownBy(() -> localAccountService.resolve(token))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_REQUIRED));
    }

    @Test
    void logoutAndAdminBulkRevocation() {
        IdentityService.LoginOutcome.Success first =
                loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.20");
        identityService.logout(first.tokens().accessToken(), first.tokens().refreshToken());
        assertThatThrownBy(() -> localAccountService.resolve(first.tokens().accessToken()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_REQUIRED));
        assertThatThrownBy(() -> identityService.refresh(first.tokens().refreshToken()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_INVALID));

        // 批量撤销：管理员一键吊销该用户全部会话
        IdentityService.LoginOutcome.Success again =
                loginSuccess(studentNo, ExternalAssertion.Type.STUDENT, "在册学生", "10.5.0.20");
        asAdmin();
        identityService.revokeAllSessions(again.appUserId());
        assertAuditContains(AuditActions.SESSIONS_REVOKED);
        assertThatThrownBy(() -> localAccountService.resolve(again.tokens().accessToken()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_REQUIRED));
        assertThatThrownBy(() -> identityService.refresh(again.tokens().refreshToken()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_INVALID));
    }

    // ============================== 管理员双因子（TOTP） ==============================

    @Test
    void adminLoginRequiresTotpEnrollmentAndCode() {
        // 首次管理员登录：必须先完成双因子绑定，不签发会话
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.21");
        String ticket = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.21", null);
        assertThat(outcome).isInstanceOf(IdentityService.LoginOutcome.MfaEnrollmentRequired.class);
        String enrollToken = ((IdentityService.LoginOutcome.MfaEnrollmentRequired) outcome).enrollToken();

        // 绑定 TOTP 并用当前合法验证码确认
        TotpService.Enrollment enrollment = identityService.startTotpEnrollment(enrollToken);
        byte[] secret = TotpService.base32Decode(enrollment.base32Secret());
        String validCode = TotpService.currentCode(secret, clock);
        identityService.confirmTotpEnrollment(enrollToken, validCode);
        assertAuditContains(AuditActions.MFA_CONFIRMED);

        // 未带验证码 → 要求第二因子
        IdentityService.LoginStart start2 = identityService.startLogin(CALLBACK, "10.5.0.21");
        String ticket2 = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start2.state(), ticket2, CALLBACK, "10.5.0.21", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_REQUIRED));

        // 错误验证码 → 拒绝
        IdentityService.LoginStart start3 = identityService.startLogin(CALLBACK, "10.5.0.21");
        String ticket3 = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start3.state(), ticket3, CALLBACK, "10.5.0.21", "000000"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_FAILED));

        // 正确验证码 → 登录成功
        IdentityService.LoginStart start4 = identityService.startLogin(CALLBACK, "10.5.0.21");
        String ticket4 = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        IdentityService.LoginOutcome adminOutcome = identityService.completeTicketLogin(
                start4.state(), ticket4, CALLBACK, "10.5.0.21", TotpService.currentCode(secret, clock));
        assertThat(adminOutcome).isInstanceOf(IdentityService.LoginOutcome.Success.class);
        assertThat(((IdentityService.LoginOutcome.Success) adminOutcome).role()).isEqualTo("ADMIN");
    }

    @Test
    void adminLoginLockedAfterRepeatedMfaFailures() {
        enrollAdminTotp();
        byte[] secret = currentAdminSecret();

        // 连续 5 次错误验证码触发账号维度锁定
        for (int i = 0; i < 5; i++) {
            IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.22");
            String ticket = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                    ExternalAssertion.Type.STAFF, "管理员", true));
            assertThatThrownBy(() -> identityService.completeTicketLogin(
                    start.state(), ticket, CALLBACK, "10.5.0.22", "000000"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_FAILED));
        }
        // 锁定期间即使验证码正确也拒绝，并留下锁定审计
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.22");
        String ticket = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        assertThatThrownBy(() -> identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.22", TotpService.currentCode(secret, clock)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED));
        assertAuditContains(AuditActions.LOGIN_LOCKED);
    }

    // ============================== 辅助方法 ==============================

    private IdentityService.LoginOutcome.Success loginSuccess(String externalNo,
                                                              ExternalAssertion.Type type,
                                                              String name, String ip) {
        String ticket = simulated.issueTicket(new ExternalAssertion(externalNo, type, name, true));
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, ip);
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, ip, null);
        assertThat(outcome).isInstanceOf(IdentityService.LoginOutcome.Success.class);
        return (IdentityService.LoginOutcome.Success) outcome;
    }

    private void enrollAdminTotp() {
        IdentityService.LoginStart start = identityService.startLogin(CALLBACK, "10.5.0.23");
        String ticket = simulated.issueTicket(new ExternalAssertion(ADMIN_NO,
                ExternalAssertion.Type.STAFF, "管理员", true));
        IdentityService.LoginOutcome outcome = identityService.completeTicketLogin(
                start.state(), ticket, CALLBACK, "10.5.0.23", null);
        String enrollToken = ((IdentityService.LoginOutcome.MfaEnrollmentRequired) outcome).enrollToken();
        TotpService.Enrollment enrollment = identityService.startTotpEnrollment(enrollToken);
        identityService.confirmTotpEnrollment(enrollToken,
                TotpService.currentCode(TotpService.base32Decode(enrollment.base32Secret()), clock));
    }

    private byte[] currentAdminSecret() {
        // 直接解密当前管理员 TOTP 密钥，用于生成合法验证码
        var totp = jdbcTemplate.queryForObject(
                "select secret_encrypted from admin_totp where app_user_id = (select id from app_user where login_name = ?)",
                String.class, "edu-" + ADMIN_NO);
        return totpService.decrypt(totp);
    }

    private void assertAuditContains(String action) {
        assertThat(auditEventRepository.findAll())
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo(action));
    }
}
