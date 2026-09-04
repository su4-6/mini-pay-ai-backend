package com.minipay.identity.application.service;

public class AccountSecurityRejectedException extends RuntimeException {
    private final String code;
    private final Long retryAfterSeconds;
    public AccountSecurityRejectedException(String code) { this(code, null); }
    public AccountSecurityRejectedException(String code, Long retryAfterSeconds) {
        super(code);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public String code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
