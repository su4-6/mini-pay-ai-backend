package com.minipay.payment.infrastructure.security;

/** Indicates that merchant application secrets cannot be encrypted safely. */
public final class MerchantSecretConfigurationException extends IllegalStateException {
    public MerchantSecretConfigurationException(String message) {
        super(message);
    }

    public MerchantSecretConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
