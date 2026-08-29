package oj.identity;

/**
 * 适配器熔断信号：页面结构变化、验证码出现、关键字段缺失等。
 * 捕获后必须停止自动认证并告警，禁止绕过验证码。
 */
public class AdapterHaltException extends RuntimeException {

    private final String reason;

    public AdapterHaltException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** 熔断原因（写入适配器状态与审计，不含页面内容）。 */
    public String reason() {
        return reason;
    }
}
