package com.minipay.identity.application.service;

public final class PaymentAuthorizationRejectedException extends RuntimeException {
    private final String code;

    public PaymentAuthorizationRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
