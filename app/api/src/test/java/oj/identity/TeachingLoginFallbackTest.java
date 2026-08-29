package oj.identity;

import oj.auth.LocalAccountService;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验收标准 473：教务独占模式（生产）下禁止本地密码/合成账号降级。
 *
 * <p>以独立上下文启用 {@code enforce-exclusive=true}：即使本地合成账号开关被误开，
 * 本地登录与建号也必须被拒绝——教务故障表现为服务不可用，而不是认证强度下降。</p>
 */
@SpringBootTest(properties = {
        "oj.identity.education.enforce-exclusive=true",
        "oj.auth.local-accounts-enabled=true"
})
@ActiveProfiles("test")
class TeachingLoginFallbackTest {

    @Autowired
    private LocalAccountService localAccountService;

    @Test
    void localLoginRejectedInExclusiveMode() {
        assertThatThrownBy(() -> localAccountService.login("test-admin", "test-admin-password"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOCAL_ACCOUNTS_DISABLED));
    }

    @Test
    void localAccountCreationRejectedInExclusiveMode() {
        assertThatThrownBy(() -> localAccountService.createLocalAccount(
                        oj.auth.AppUser.Role.STUDENT, "fallback-student", "password123", null, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LOCAL_ACCOUNTS_DISABLED));
    }
}
