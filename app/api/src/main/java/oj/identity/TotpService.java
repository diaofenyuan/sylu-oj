package oj.identity;

import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 管理员双因子（RFC 6238 TOTP）：密钥 AES-GCM 密文落库，验证窗口 ±1 步。
 */
@Service
public class TotpService {

    private static final int STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int WINDOW = 1;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminTotpRepository totpRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final byte[] encryptionKey;

    public TotpService(AdminTotpRepository totpRepository,
                       AuditService auditService,
                       Clock clock,
                       @Value("${oj.identity.totp-encryption-key:}") String encryptionKeyHex) {
        this.totpRepository = totpRepository;
        this.auditService = auditService;
        this.clock = clock;
        byte[] key = encryptionKeyHex.isBlank()
                ? new byte[32]
                : HexFormat.of().parseHex(encryptionKeyHex);
        if (key.length != 32) {
            throw new IllegalStateException("oj.identity.totp-encryption-key 必须为 32 字节（64 位十六进制）");
        }
        this.encryptionKey = key;
    }

    public boolean isConfirmed(Long appUserId) {
        return totpRepository.findByAppUserId(appUserId).map(AdminTotp::isConfirmed).orElse(false);
    }

    /** 开始绑定：生成密钥并返回 otpauth URI 与 Base32 密钥（仅本次可见）。 */
    @Transactional
    public Enrollment startEnrollment(Long appUserId, String accountName) {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        totpRepository.findByAppUserId(appUserId).ifPresent(totpRepository::delete);
        totpRepository.save(new AdminTotp(appUserId, encrypt(secret)));
        String base32 = base32Encode(secret);
        auditService.record(AuditActions.MFA_ENROLLED, "APP_USER", String.valueOf(appUserId), null, null);
        return new Enrollment(base32,
                "otpauth://totp/SYLU-OJ:" + accountName + "?secret=" + base32 + "&issuer=SYLU-OJ&digits=6&period=30");
    }

    /** 用一次性验证码确认绑定。 */
    @Transactional
    public void confirmEnrollment(Long appUserId, String code) {
        AdminTotp totp = totpRepository.findByAppUserId(appUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.MFA_FAILED));
        if (!verify(decryptSecret(totp), code)) {
            auditService.record(AuditActions.MFA_FAILED, "APP_USER", String.valueOf(appUserId), null, null);
            throw new ApiException(ErrorCode.MFA_FAILED);
        }
        totp.confirm();
        auditService.record(AuditActions.MFA_CONFIRMED, "APP_USER", String.valueOf(appUserId), null, null);
    }

    /** 校验管理员登录时提交的 TOTP 码。 */
    public boolean verifyLogin(Long appUserId, String code) {
        Optional<AdminTotp> totp = totpRepository.findByAppUserId(appUserId);
        if (totp.isEmpty() || !totp.get().isConfirmed() || code == null) {
            return false;
        }
        boolean ok = verify(decryptSecret(totp.get()), code);
        if (!ok) {
            auditService.record(AuditActions.MFA_FAILED, "APP_USER", String.valueOf(appUserId), null, null);
        }
        return ok;
    }

    private byte[] decryptSecret(AdminTotp totp) {
        return decrypt(totp.getSecretEncrypted());
    }

    private boolean verify(byte[] secret, String code) {
        long step = clock.instant().getEpochSecond() / STEP_SECONDS;
        for (long i = -WINDOW; i <= WINDOW; i++) {
            if (codeForStep(secret, step + i).equals(code)) {
                return true;
            }
        }
        return false;
    }

    static String codeForStep(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] challenge = ByteBuffer.allocate(8).putLong(step).array();
            byte[] hash = mac.doFinal(challenge);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    /** 供测试与绑定向导生成当前合法验证码。 */
    public static String currentCode(byte[] secret, Clock clock) {
        return codeForStep(secret, clock.instant().getEpochSecond() / STEP_SECONDS);
    }

    private String encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 密钥加密失败", e);
        }
    }

    byte[] decrypt(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[12];
            System.arraycopy(raw, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            return cipher.doFinal(raw, 12, raw.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 密钥解密失败", e);
        }
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /** 供测试：从 Base32 还原密钥。 */
    public static byte[] base32Decode(String base32) {
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : base32.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(Character.toUpperCase(c));
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    public record Enrollment(String base32Secret, String otpauthUri) {
    }
}
