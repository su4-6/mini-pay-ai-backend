package com.minipay.consumerbff.infrastructure.session;

/**
 * WebSession attribute names of the consumer session. Public so the security filter and the
 * application wiring read exactly the same keys the store writes.
 */
public final class ConsumerSessionAttributes {

    public static final String ACCESS_TOKEN = "minipay.consumer.access-token";
    public static final String REFRESH_TOKEN = "minipay.consumer.refresh-token";
    public static final String CONSUMER_ID = "minipay.consumer.user-id";
    public static final String MASKED_PHONE = "minipay.consumer.masked-phone";
    public static final String DISPLAY_NAME = "minipay.consumer.display-name";
    public static final String PAY_PASSWORD_SET = "minipay.consumer.pay-password-set";
    public static final String ONBOARDING_REQUIRED = "minipay.consumer.onboarding-required";
    public static final String REAL_NAME_STATUS = "minipay.consumer.real-name-status";
    public static final String REAL_NAME_VERIFIED = "minipay.consumer.real-name-verified";
    public static final String LOGIN_VERIFIER = "minipay.consumer.login.verifier";
    public static final String LOGIN_CHALLENGE = "minipay.consumer.login.challenge-id";
    public static final String LOGIN_DEVICE = "minipay.consumer.login.device-id";
    public static final String PREPARED_TRANSFER_INTENT = "minipay.consumer.transfer.prepared-intent";
    public static final String PREPARED_TRANSFER_AMOUNT = "minipay.consumer.transfer.prepared-amount";
    public static final String PREPARED_PAYMENT_ORDER = "minipay.consumer.payment.prepared-order";
    public static final String PREPARED_PAYMENT_AMOUNT = "minipay.consumer.payment.prepared-amount";

    private ConsumerSessionAttributes() {
    }
}
