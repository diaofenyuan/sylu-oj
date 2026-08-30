package oj.testdata;

import oj.shared.GcmCrypto;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 测试数据下发信封加密（AES-256-GCM）。
 * 测试数据静态加密由 DATA 区加密卷承担（Task 2/8 落地 LUKS 卷）；
 * 下发通道在 Agent 认证后按任务逐用例加密：信封密钥由 Agent 身份与其
 * 注册密钥派生（SHA-256），Agent 无需持有任何共享主密钥，且只能解密
 * 自己领取到的用例信封；Agent 在内存解密后写入本次沙箱临时写层，
 * 本地磁盘与日志不留存。
 */
@Service
public class TestdataCipher {

    public record Envelope(String algo, String keyId, String iv, String ciphertext) {
    }

    public Envelope sealForAgent(String agentId, String agentSecret, String plaintext) {
        byte[] key = deriveKey(agentId, agentSecret);
        GcmCrypto.Sealed sealed = GcmCrypto.seal(key, plaintext.getBytes(StandardCharsets.UTF_8));
        return new Envelope("AES-256-GCM", "agent-derived:" + agentId,
                Base64.getEncoder().encodeToString(sealed.iv()),
                Base64.getEncoder().encodeToString(sealed.ciphertext()));
    }

    /** 信封密钥派生：SHA-256("oj-testdata-v1|" + agentId + "|" + agentSecret)，与 Go 客户端一致。 */
    public static byte[] deriveKey(String agentId, String agentSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("oj-testdata-v1|" + agentId + "|" + agentSecret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("信封密钥派生失败", e);
        }
    }
}
