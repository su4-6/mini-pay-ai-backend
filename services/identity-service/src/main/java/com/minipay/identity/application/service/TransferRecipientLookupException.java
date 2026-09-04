package com.minipay.identity.application.service;

public class TransferRecipientLookupException extends RuntimeException {
    private final String code;

    public TransferRecipientLookupException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
