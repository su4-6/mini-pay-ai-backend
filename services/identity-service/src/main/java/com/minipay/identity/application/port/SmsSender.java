package com.minipay.identity.application.port;

public interface SmsSender {
    void sendLoginCode(String normalizedPhone, String code);

    default void sendConsumerLoginCode(String normalizedPhone, String code) {
        sendLoginCode(normalizedPhone, code);
    }

    /**
     * 发送验证码并返回渠道实际生效的验证码（如阿里云号码认证由云端生成）。
     * 返回 {@code null} 表示渠道不自行生成验证码，调用方继续用自己生成的码。
     */
    default String sendAndGetLoginCode(String normalizedPhone) {
        return null;
    }

    default String sendAndGetConsumerLoginCode(String normalizedPhone) {
        return sendAndGetLoginCode(normalizedPhone);
    }

    default String demoCodeForView() {
        return null;
    }
}
