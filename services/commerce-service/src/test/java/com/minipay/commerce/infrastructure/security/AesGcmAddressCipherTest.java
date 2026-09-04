package com.minipay.commerce.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.commerce.application.port.AddressCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmAddressCipherTest {
    private static final byte[] KEY = "minipay-commerce-demo-key-32byte".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptsWithRandomIvAndConfiguredKeyVersion() throws Exception {
        AesGcmAddressCipher cipher = new AesGcmAddressCipher(Base64.getEncoder().encodeToString(KEY), 7);

        AddressCipher.EncryptedValue first = cipher.encrypt("幸福路 88 号");
        AddressCipher.EncryptedValue second = cipher.encrypt("幸福路 88 号");

        assertThat(first.keyVersion()).isEqualTo(7);
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(decrypt(first.ciphertext())).isEqualTo("幸福路 88 号");
    }

    @Test
    void rejectsBlankValuesAndNon256BitKeys() {
        assertThatThrownBy(() -> new AesGcmAddressCipher(
                Base64.getEncoder().encodeToString(new byte[16]), 1))
                .isInstanceOf(IllegalArgumentException.class);
        AesGcmAddressCipher cipher = new AesGcmAddressCipher(Base64.getEncoder().encodeToString(KEY), 1);
        assertThatThrownBy(() -> cipher.encrypt(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String decrypt(byte[] value) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        byte[] iv = new byte[12];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
