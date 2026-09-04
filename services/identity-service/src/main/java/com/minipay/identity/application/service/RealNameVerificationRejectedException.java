package com.minipay.identity.application.service;

public final class RealNameVerificationRejectedException extends RuntimeException {
    private final String code;

    public RealNameVerificationRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
