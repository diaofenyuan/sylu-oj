package oj.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** 身份模块内部哈希与随机数工具：令牌仅以 SHA-256 哈希落库。 */
final class Hashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashing() {
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
