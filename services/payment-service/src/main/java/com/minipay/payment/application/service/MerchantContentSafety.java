package com.minipay.payment.application.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sandbox baseline for merchant-owned display names. Production can replace this policy with an
 * asynchronous provider, but user-controlled names must never bypass validation because a remote
 * provider is unavailable.
 */
@Component
public class MerchantContentSafety {
    private static final List<String> BLOCKED_TERMS = List.of("赌博", "博彩", "色情", "毒品", "诈骗", "洗钱", "枪支");

    public void checkMerchantName(String value) {
        check(value, "MERCHANT_CONTENT_REJECTED");
    }

    public void checkApplicationName(String value) {
        check(value, "APPLICATION_CONTENT_REJECTED");
    }

    private static void check(String value, String code) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.chars().anyMatch(Character::isISOControl)
                || BLOCKED_TERMS.stream().anyMatch(normalized::contains)) {
            throw new PaymentProblemException(code, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
