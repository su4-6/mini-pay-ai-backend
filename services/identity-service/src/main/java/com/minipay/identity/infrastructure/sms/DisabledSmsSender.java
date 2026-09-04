package com.minipay.identity.infrastructure.sms;

import com.minipay.identity.application.port.SmsSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.identity.sms.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public class DisabledSmsSender implements SmsSender {
    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        throw new SmsDeliveryUnavailableException();
    }
}
