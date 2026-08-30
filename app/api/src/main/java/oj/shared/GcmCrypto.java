package oj.shared;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM 帮助类：测试数据下发信封与 Agent 密钥密文共用。
 * 密钥以 64 位十六进制（32 字节）配置注入，仓库中不得出现真实密钥。
 */
public final class GcmCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private GcmCrypto() {
    }

    public static byte[] decodeHexKey(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalStateException("加密密钥必须为 64 位十六进制（32 字节）");
        }
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return key;
    }

    public record Sealed(byte[] iv, byte[] ciphertext) {
    }

    public static Sealed seal(byte[] key, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new Sealed(iv, cipher.doFinal(plaintext));
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public static byte[] open(byte[] key, byte[] iv, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalArgumentException("解密失败或数据被篡改", e);
        }
    }

    /** 常量时间十六进制字符串比较，防止签名校验时序侧信道。 */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(Arrays.copyOf(a == null ? new byte[0] : a.getBytes(),
                a == null ? 0 : a.length()), b == null ? new byte[0] : b.getBytes());
    }
}
