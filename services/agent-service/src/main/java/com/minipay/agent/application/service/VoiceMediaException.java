package com.minipay.agent.application.service;

public final class VoiceMediaException extends RuntimeException {
    private final String code;
    public VoiceMediaException(String code) { super(code); this.code = code; }
    public VoiceMediaException(String code, Throwable cause) { super(code, cause); this.code = code; }
    public String code() { return code; }
}
