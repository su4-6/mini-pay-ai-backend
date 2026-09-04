package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhoneDisclosureCipherTest {
    private final PhoneDisclosureCipher cipher = new PhoneDisclosureCipher(
            "test-phone-disclosure-key-at-least-32-characters", "test-v1");

    @Test
    void encryptsWithUserBindingAndRandomNonces() {
        UUID user = UUID.randomUUID();
        PhoneDisclosureCipher.EncryptedPhone first = cipher.encrypt(user, "13800138000");
        PhoneDisclosureCipher.EncryptedPhone second = cipher.encrypt(user, "13800138000");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(cipher.decrypt(user, first.ciphertext(), first.nonce(), first.keyId()))
                .isEqualTo("13800138000");
        assertThatThrownBy(() -> cipher.decrypt(
                UUID.randomUUID(), first.ciphertext(), first.nonce(), first.keyId()))
                .isInstanceOf(PhoneDisclosureCipher.PhoneDisclosureUnavailableException.class);
    }

    @Test
    void rejectsUnknownKeyVersions() {
        UUID user = UUID.randomUUID();
        PhoneDisclosureCipher.EncryptedPhone encrypted = cipher.encrypt(user, "13800138000");
        assertThatThrownBy(() -> cipher.decrypt(
                user, encrypted.ciphertext(), encrypted.nonce(), "retired-key"))
                .isInstanceOf(PhoneDisclosureCipher.PhoneDisclosureUnavailableException.class);
    }
}
