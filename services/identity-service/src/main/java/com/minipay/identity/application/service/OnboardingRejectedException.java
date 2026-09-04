package com.minipay.identity.application.service;

public final class OnboardingRejectedException extends RuntimeException {
    private final String code;

    public OnboardingRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
