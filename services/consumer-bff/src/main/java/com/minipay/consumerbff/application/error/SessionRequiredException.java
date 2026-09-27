package com.minipay.consumerbff.application.error;

import org.springframework.http.HttpStatus;

/**
 * The browser session can no longer be used: no tokens, refresh rejected, or the upstream answered
 * 401 twice. {@code SessionControllerAdvice} maps it to 401 and clears the session cookie state.
 */
public class SessionRequiredException extends RuntimeException {

    public SessionRequiredException(String code) {
        super(code, null, false, false);
    }

    public static SessionRequiredException expired() {
        return new SessionRequiredException("SESSION_EXPIRED");
    }

    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }
}
