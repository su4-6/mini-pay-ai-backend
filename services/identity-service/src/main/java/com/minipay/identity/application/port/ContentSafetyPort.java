package com.minipay.identity.application.port;

import java.net.URI;

public interface ContentSafetyPort {
    boolean isNicknameAllowed(String nickname);

    boolean isImageAllowed(URI signedImageUrl);
}
