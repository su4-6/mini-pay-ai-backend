package com.minipay.payment.infrastructure.security;

import java.util.UUID;

/** Authenticated merchant-application context attached to a merchant-api request. */
public record MerchantApiContext(
        UUID applicationId,
        UUID merchantId,
        String appId,
        String secret,
        String permissions,
        String availableChannels) {
}
