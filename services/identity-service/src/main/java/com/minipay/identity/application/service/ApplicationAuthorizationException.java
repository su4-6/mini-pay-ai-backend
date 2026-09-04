package com.minipay.identity.application.service;

public final class ApplicationAuthorizationException extends RuntimeException {
    private final String code;

    public ApplicationAuthorizationException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
