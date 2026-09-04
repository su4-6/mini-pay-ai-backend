package com.minipay.identity.application.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailAddressService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final byte[] pepper;

    public EmailAddressService(@Value("${minipay.identity.email-hash-pepper}") String pepper) {
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String normalize(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) throw new AccountSecurityRejectedException("EMAIL_INVALID");
        return value;
    }

    public byte[] hmac(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest email", exception);
        }
    }

    public String mask(String normalized) {
        int at = normalized.indexOf('@');
        String local = normalized.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, Math.min(3, local.length()));
        return visible + "***" + normalized.substring(at);
    }
}
