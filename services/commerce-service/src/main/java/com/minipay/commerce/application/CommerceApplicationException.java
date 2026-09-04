package com.minipay.commerce.application;

public final class CommerceApplicationException extends RuntimeException {
    private final String code;

    public CommerceApplicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
