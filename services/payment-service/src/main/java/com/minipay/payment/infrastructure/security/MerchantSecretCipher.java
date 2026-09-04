package com.minipay.payment.infrastructure.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts merchant application secrets at rest. The plaintext is never persisted. */
@Component
public class MerchantSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private final SecretKey encryptionKey;

    public MerchantSecretCipher(@Value("${minipay.merchant.secret-encryption-key:}") String encodedKey) {
        this.encryptionKey = decodeKey(encodedKey);
    }

    public SecretMaterial newSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new SecretMaterial(plaintext, encrypt(plaintext));
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (Exception exception) {
            throw new IllegalStateException("MERCHANT_SECRET_ENCRYPTION_FAILED", exception);
        }
    }

    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= IV_LENGTH) {
            throw new IllegalArgumentException("Invalid merchant secret ciphertext");
        }
        try {
            ByteBuffer source = ByteBuffer.wrap(ciphertext);
            byte[] iv = new byte[IV_LENGTH];
            source.get(iv);
            byte[] encrypted = new byte[source.remaining()];
            source.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("MERCHANT_SECRET_DECRYPTION_FAILED", exception);
        }
    }

    private static SecretKey decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new MerchantSecretConfigurationException("MERCHANT_APP_SECRET_KEY is required");
        }
        final byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new MerchantSecretConfigurationException(
                    "MERCHANT_APP_SECRET_KEY must be valid Base64", exception);
        }
        if (key.length != KEY_LENGTH_BYTES) {
            throw new MerchantSecretConfigurationException(
                    "MERCHANT_APP_SECRET_KEY must be a base64 encoded 32-byte key");
        }
        return new javax.crypto.spec.SecretKeySpec(key, "AES");
    }

    public record SecretMaterial(String plaintext, byte[] ciphertext) { }
}
