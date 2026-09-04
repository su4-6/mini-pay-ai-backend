package com.minipay.identity.infrastructure.sms;

import com.minipay.identity.application.port.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local integration adapter that exposes an SMS code in the process console.
 *
 * <p>This deliberately handles a credential in console output and must never be
 * enabled outside a developer workstation.</p>
 */
@Component
@Profile("console-sms")
@ConditionalOnProperty(name = "minipay.identity.sms.provider", havingValue = "console")
public class ConsoleSmsSender implements SmsSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleSmsSender.class);

    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        LOGGER.warn(
                "[LOCAL-ONLY][SENSITIVE] Simulated SMS login code: mobile={}, code={}",
                mask(normalizedPhone),
                code);
    }

    static String mask(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.length() < 7) {
            return "****";
        }
        return normalizedPhone.substring(0, 3)
                + "****"
                + normalizedPhone.substring(normalizedPhone.length() - 4);
    }
}
