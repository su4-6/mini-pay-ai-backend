package com.minipay.identity.infrastructure.email;

import com.minipay.identity.application.port.EmailSender;
import com.minipay.identity.application.service.AccountSecurityRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.identity.email.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public class DisabledEmailSender implements EmailSender {
    @Override public void sendVerificationCode(String email, String code) {
        throw new AccountSecurityRejectedException("EMAIL_DELIVERY_UNAVAILABLE");
    }
}
