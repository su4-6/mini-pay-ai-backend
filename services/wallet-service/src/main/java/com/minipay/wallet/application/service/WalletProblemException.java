package com.minipay.wallet.application.service;

import org.springframework.http.HttpStatus;

public final class WalletProblemException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public WalletProblemException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
