package com.minipay.consumerbff.domain.identity;

/**
 * Masks a mainland China mobile number for display. The BFF receives the raw mobile transiently
 * during SMS login and must never forward, persist or log it, so every value that leaves the BFF
 * passes through here first.
 */
public final class MobileMasking {

    private MobileMasking() {
    }

    public static String mask(String mobile) {
        if (mobile == null) {
            return "";
        }
        String trimmed = mobile.trim();
        if (trimmed.length() < 7) {
            return trimmed.isEmpty() ? "" : "***";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
