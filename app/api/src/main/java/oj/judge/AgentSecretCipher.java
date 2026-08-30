package oj.judge;

import oj.shared.GcmCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Agent 签名密钥静态加密（AES-256-GCM，密钥经部署注入）。
 * 密文格式：base64(iv):base64(ciphertext+tag)。
 */
@Service
public class AgentSecretCipher {

    private final byte[] key;

    public AgentSecretCipher(@Value("${oj.judge.crypto-key}") String hexKey) {
        this.key = GcmCrypto.decodeHexKey(hexKey);
    }

    public String encrypt(String secret) {
        GcmCrypto.Sealed sealed = GcmCrypto.seal(key, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sealed.iv()) + ":"
                + Base64.getEncoder().encodeToString(sealed.ciphertext());
    }

    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Agent 密钥密文格式非法");
        }
        byte[] plain = GcmCrypto.open(key,
                Base64.getDecoder().decode(parts[0]),
                Base64.getDecoder().decode(parts[1]));
        return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }
}
