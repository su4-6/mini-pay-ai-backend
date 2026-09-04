package com.minipay.identity.infrastructure.realname;

import com.minipay.identity.application.port.RealNameVerificationPort;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.identity.real-name.provider", havingValue = "sandbox")
public final class SandboxRealNameVerification implements RealNameVerificationPort {
    @Override
    public VerificationResult verify(String legalName, String idNumber, byte[] faceJpeg) {
        boolean verified = !idNumber.endsWith("0");
        return new VerificationResult(
                verified,
                "sandbox-" + UUID.randomUUID(),
                verified ? null : "SANDBOX_IDENTITY_REJECTED");
    }
}
