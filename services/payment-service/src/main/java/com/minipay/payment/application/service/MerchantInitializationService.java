package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.security.MerchantSecretConfigurationException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Coordinates remote wallet provisioning before the local merchant transaction starts. */
@Service
public class MerchantInitializationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantInitializationService.class);
    private final WalletInternalClient wallets;
    private final MerchantService merchants;

    public MerchantInitializationService(
            WalletInternalClient wallets, MerchantService merchants) {
        this.wallets = wallets;
        this.merchants = merchants;
    }

    public MerchantService.InitializationView initialize(UUID ownerUserId, UUID merchantId) {
        // Fail before the remote write when the merchant does not belong to the
        // authenticated owner.
        merchants.merchant(ownerUserId, merchantId);
        // merchantId is already UUIDv7 and is stable across retries. Wallet also
        // protects OPENING_GRANT by its owner-bound business number.
        wallets.openWallet(merchantId, ownerUserId);
        try {
            return merchants.initialize(ownerUserId, merchantId);
        } catch (MerchantSecretConfigurationException exception) {
            LOGGER.error("Merchant initialization configuration is invalid", exception);
            throw new PaymentProblemException(
                    "MERCHANT_INITIALIZATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, exception);
        }
    }
}
