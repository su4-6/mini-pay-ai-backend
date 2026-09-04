package com.minipay.payment.application.service;

import org.springframework.http.HttpStatus;

public class OpsBusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OpsBusinessException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
