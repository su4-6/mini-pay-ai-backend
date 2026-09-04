package com.minipay.payment.application.service;

import org.springframework.http.HttpStatus;

public final class PaymentProblemException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public PaymentProblemException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public PaymentProblemException(String code, HttpStatus status, Throwable cause) {
        super(code, cause);
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
