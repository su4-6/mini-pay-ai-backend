package com.minipay.identity.infrastructure.moderation;

import com.minipay.identity.application.port.ContentSafetyPort;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.identity.content-safety.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public final class DisabledContentSafety implements ContentSafetyPort {
    @Override public boolean isNicknameAllowed(String nickname) { return true; }
    @Override public boolean isImageAllowed(URI signedImageUrl) { return true; }
}
