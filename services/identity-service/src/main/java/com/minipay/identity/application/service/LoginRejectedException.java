package com.minipay.identity.application.service;

import java.util.UUID;

public class LoginRejectedException extends RuntimeException {
    private final String code;
    private final UUID userId;
    private final Long retryAfterSeconds;

    public LoginRejectedException(String code) {
        this(code, null, null);
    }

    public LoginRejectedException(String code, UUID userId) {
        this(code, userId, null);
    }

    public LoginRejectedException(String code, Long retryAfterSeconds) {
        this(code, null, retryAfterSeconds);
    }

    private LoginRejectedException(String code, UUID userId, Long retryAfterSeconds) {
        super("登录失败，请检查手机号、密码或验证码");
        this.code = code;
        this.userId = userId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public UUID userId() {
        return userId;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
