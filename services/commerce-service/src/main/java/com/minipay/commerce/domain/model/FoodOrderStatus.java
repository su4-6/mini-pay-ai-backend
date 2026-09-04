package com.minipay.commerce.domain.model;

public enum FoodOrderStatus {
    PENDING_PAYMENT,
    PAID,
    CANCELLATION_PENDING,
    REFUND_FAILED,
    MERCHANT_ACCEPTED,
    PREPARING,
    DELIVERING,
    DELIVERED,
    CANCELLED;

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
