package com.minipay.identity.infrastructure.sms;

public final class SmsDeliveryUnavailableException extends RuntimeException {
    public SmsDeliveryUnavailableException() {
        super("SMS delivery is temporarily unavailable");
    }

    public SmsDeliveryUnavailableException(Throwable cause) {
        super("SMS delivery is temporarily unavailable", cause);
    }
}
