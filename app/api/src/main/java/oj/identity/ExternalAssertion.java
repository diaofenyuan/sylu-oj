package oj.identity;

/**
 * 教务系统对一次登录的身份断言：仅学号/工号与姓名等最小字段。
 * 教务密码、Cookie、验证码一律不出现在本对象中。
 *
 * <p>type 为 {@code UNKNOWN} 时，由身份服务按教学组织建档记录推导角色。</p>
 */
public record ExternalAssertion(String externalNo, Type type, String name, boolean active) {

    public enum Type {STUDENT, STAFF, UNKNOWN}

    /** 断言指纹：学号 + 姓名哈希，用于识别学号复用/身份变更。 */
    public String fingerprint() {
        return Hashing.sha256(externalNo + "|" + (name == null ? "" : name));
    }
}
