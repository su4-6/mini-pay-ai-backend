package com.minipay.identity.infrastructure.realname;

import com.minipay.identity.application.port.RealNameVerificationPort;
import com.minipay.identity.application.service.RealNameVerificationRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.identity.real-name.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public final class DisabledRealNameVerification implements RealNameVerificationPort {
    @Override
    public VerificationResult verify(String legalName, String idNumber, byte[] faceJpeg) {
        throw new RealNameVerificationRejectedException("REAL_NAME_PROVIDER_UNAVAILABLE");
    }
}
