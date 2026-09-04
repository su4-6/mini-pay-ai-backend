package com.minipay.agent.application.service;

public final class AgentApplicationException extends RuntimeException {
    private final String code;

    public AgentApplicationException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
