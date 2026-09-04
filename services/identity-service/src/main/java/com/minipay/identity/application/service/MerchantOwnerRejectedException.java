package com.minipay.identity.application.service;

public final class MerchantOwnerRejectedException extends RuntimeException {
    private final String code;

    public MerchantOwnerRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
