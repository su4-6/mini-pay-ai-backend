package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PaymentProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

final class ConsumerClaims {
    private ConsumerClaims() {
    }

    static UUID requireOnboardedUser(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaim("onboarding_completed"))) {
            throw new PaymentProblemException(
                    "ONBOARDING_REQUIRED", HttpStatus.FORBIDDEN);
        }
        return requireSubject(jwt);
    }

    static UUID requireReadyUser(Jwt jwt, boolean requirePaymentPassword) {
        UUID userId = requireOnboardedUser(jwt);
        if (!Boolean.TRUE.equals(jwt.getClaim("real_name_verified"))) {
            String status = jwt.getClaimAsString("real_name_status");
            throw new PaymentProblemException(
                    "PROCESSING".equals(status)
                            ? "REAL_NAME_VERIFICATION_PROCESSING"
                            : "REAL_NAME_VERIFICATION_REQUIRED",
                    HttpStatus.FORBIDDEN);
        }
        if (requirePaymentPassword && !Boolean.TRUE.equals(jwt.getClaim("pay_password_set"))) {
            throw new PaymentProblemException(
                    "PAYMENT_PASSWORD_REQUIRED", HttpStatus.FORBIDDEN);
        }
        return userId;
    }

    private static UUID requireSubject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new PaymentProblemException(
                    "INVALID_CONSUMER_SUBJECT", HttpStatus.UNAUTHORIZED);
        }
    }

    static String requireDeviceId(Jwt jwt) {
        String deviceId = jwt.getClaimAsString("device_id");
        if (deviceId == null || deviceId.isBlank()) {
            throw new PaymentProblemException("DEVICE_ID_INVALID", HttpStatus.UNAUTHORIZED);
        }
        return deviceId;
    }
}
