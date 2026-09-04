package com.minipay.identity.application.service;

public final class ProfileRejectedException extends RuntimeException {
    private final String code;

    public ProfileRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public ProfileRejectedException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
