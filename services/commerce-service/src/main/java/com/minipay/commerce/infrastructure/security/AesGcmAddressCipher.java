package com.minipay.commerce.infrastructure.security;

import com.minipay.commerce.application.port.AddressCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmAddressCipher implements AddressCipher {
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final int keyVersion;
    private final SecureRandom random = new SecureRandom();

    public AesGcmAddressCipher(
            @Value("${minipay.commerce.address-encryption-key}") String encodedKey,
            @Value("${minipay.commerce.address-encryption-key-version:1}") int keyVersion) {
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length != 32) throw new IllegalArgumentException(
                "Commerce address encryption key must be 256 bits");
        this.key = new SecretKeySpec(decoded, "AES");
        this.keyVersion = keyVersion;
    }

    @Override
    public EncryptedValue encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Address sensitive field is required");
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array(), keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt delivery address", exception);
        }
    }
}
