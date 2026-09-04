package com.minipay.agent.application.service;

public final class ModelGatewayException extends RuntimeException {
    private final String code;

    public ModelGatewayException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ModelGatewayException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
