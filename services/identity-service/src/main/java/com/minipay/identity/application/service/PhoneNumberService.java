package com.minipay.identity.application.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PhoneNumberService {
    private static final Pattern MAINLAND_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private final byte[] pepper;

    public PhoneNumberService(@Value("${minipay.identity.phone-hash-pepper}") String pepper) {
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String normalize(String rawPhone) {
        String phone = rawPhone == null ? "" : rawPhone.replaceAll("\\s+", "");
        if (!MAINLAND_MOBILE.matcher(phone).matches()) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        return phone;
    }

    public byte[] hash(String normalizedPhone) {
        return hmac(normalizedPhone);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to hash phone number", exception);
        }
    }

    public String hashHex(String normalizedPhone) {
        return HexFormat.of().formatHex(hash(normalizedPhone));
    }

    public String mask(String normalizedPhone) {
        return normalizedPhone.substring(0, 3) + "****" + normalizedPhone.substring(7);
    }

    public boolean matchesHash(String normalizedPhone, byte[] expected) {
        return MessageDigest.isEqual(hash(normalizedPhone), expected);
    }
}
