package com.minipay.payment.infrastructure.security;

import com.minipay.payment.infrastructure.security.MerchantSecretCipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MerchantSecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(
            "merchant-app-secret-aes-key-32b!".getBytes(StandardCharsets.UTF_8));

    @Test
    void generatesASecretAndPersistsOnlyCiphertext() {
        MerchantSecretCipher cipher = new MerchantSecretCipher(KEY);
        MerchantSecretCipher.SecretMaterial secret = cipher.newSecret();

        assertThat(secret.plaintext()).hasSizeGreaterThan(32);
        assertThat(secret.ciphertext()).hasSizeGreaterThan(secret.plaintext().length());
        assertThat(new String(secret.ciphertext(), StandardCharsets.UTF_8)).doesNotContain(secret.plaintext());
        assertThat(cipher.decrypt(secret.ciphertext())).isEqualTo(secret.plaintext());
    }

    @Test
    void rejectsMissingEncryptionKeyAtStartup() {
        assertThatThrownBy(() -> new MerchantSecretCipher(""))
                .isInstanceOf(MerchantSecretConfigurationException.class)
                .hasMessage("MERCHANT_APP_SECRET_KEY is required");
    }

    @Test
    void rejectsMalformedEncryptionKeyAtStartup() {
        assertThatThrownBy(() -> new MerchantSecretCipher("not-base64"))
                .isInstanceOf(MerchantSecretConfigurationException.class)
                .hasMessage("MERCHANT_APP_SECRET_KEY must be valid Base64");
    }
}
