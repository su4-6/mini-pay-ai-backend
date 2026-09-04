package com.minipay.identity.infrastructure.sms;

import com.minipay.identity.application.port.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo-auth")
@ConditionalOnProperty(name = "minipay.identity.sms.provider", havingValue = "demo")
public class DemoSmsSender implements SmsSender {
    private final String demoCode;

    public DemoSmsSender(@Value("${minipay.identity.sms.demo-code}") String demoCode) {
        this.demoCode = demoCode;
    }

    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        // Deliberately no logging: verification codes are credentials.
    }

    @Override
    public String demoCodeForView() {
        return demoCode;
    }
}
