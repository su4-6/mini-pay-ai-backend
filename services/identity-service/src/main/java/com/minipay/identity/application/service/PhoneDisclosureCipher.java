package com.minipay.identity.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PhoneDisclosureCipher {
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public PhoneDisclosureCipher(
            @Value("${minipay.identity.phone-disclosure.encryption-key}") String configuredKey,
            @Value("${minipay.identity.phone-disclosure.key-id:v1}") String keyId) {
        if (configuredKey == null || configuredKey.length() < 32) {
            throw new IllegalStateException("Phone disclosure encryption key must contain at least 32 characters");
        }
        this.key = new SecretKeySpec(sha256(configuredKey), "AES");
        this.keyId = keyId;
    }

    public EncryptedPhone encrypt(UUID userId, String mobile) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
            return new EncryptedPhone(
                    cipher.doFinal(mobile.getBytes(StandardCharsets.UTF_8)), nonce, keyId);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt phone disclosure", exception);
        }
    }

    public String decrypt(UUID userId, byte[] ciphertext, byte[] nonce, String storedKeyId) {
        if (ciphertext == null || nonce == null || !keyId.equals(storedKeyId)) {
            throw new PhoneDisclosureUnavailableException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new PhoneDisclosureUnavailableException();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record EncryptedPhone(byte[] ciphertext, byte[] nonce, String keyId) { }

    public static final class PhoneDisclosureUnavailableException extends RuntimeException { }
}
