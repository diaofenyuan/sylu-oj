package oj.identity;

import java.util.Optional;

/**
 * 教务身份提供方（IdP）抽象。
 *
 * <p>生产实现为教务登录页适配器（见 {@code JxwEducationProvider} 与
 * {@code docs/runbooks/education-login-adapter.md}）；开发/内测使用模拟器。
 * 任何实现都不得把教务密码、Cookie、验证码或完整页面内容交给调用方。</p>
 */
public interface EducationIdentityProvider {

    /** 提供方名称（写入审计与运行手册）。 */
    String name();

    /** 教务系统当前是否可用。不可用时必须拒绝新登录，禁止任何本地降级。 */
    boolean isAvailable();

    /** 教务登录页地址（跳转登录入口）。 */
    String loginPageUrl();

    /** 是否支持服务端代提交模式（需上线审批登记实现方式与责任人）。 */
    default boolean supportsProxySubmission() {
        return false;
    }

    /**
     * 采集登录页结构指纹（关键字段哈希）。
     * 页面结构、验证码或关键字段异常时抛出 {@link AdapterHaltException}。
     */
    String capturePageFingerprint();

    /**
     * 二次校验教务票据并返回身份断言；禁止只做本地格式检查。
     * 票据无效抛 {@code ApiException(TICKET_INVALID)}。
     */
    ExternalAssertion verifyTicket(String ticket);

    /**
     * 服务端代提交登录（仅在生产登记后启用）。
     * 密码仅在请求内存中短暂存在，调用方负责在使用后立即清零。
     */
    default Optional<ExternalAssertion> proxyLogin(String externalNo, char[] password) {
        return Optional.empty();
    }
}
