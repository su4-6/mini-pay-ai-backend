package com.minipay.commerce.domain.model;

public final class CommerceDomainException extends RuntimeException {
    private final String code;

    public CommerceDomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
