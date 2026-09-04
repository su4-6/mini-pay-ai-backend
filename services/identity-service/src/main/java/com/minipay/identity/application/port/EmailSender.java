package com.minipay.identity.application.port;

public interface EmailSender {
    void sendVerificationCode(String email, String code);
}
