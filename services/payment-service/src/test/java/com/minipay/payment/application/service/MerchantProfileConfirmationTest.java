package com.minipay.payment.application.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantProfileConfirmationTest {

    @Test
    void operationCreatedMerchantRequiresConfirmationUntilOwnerSaves() {
        assertTrue(MerchantService.MerchantView.from(merchant("OPS", null)).profileConfirmationRequired());
        assertFalse(MerchantService.MerchantView.from(merchant("OPS", Instant.now())).profileConfirmationRequired());
    }

    @Test
    void onboardingMerchantNeverShowsOperationCreatedHint() {
        assertFalse(MerchantService.MerchantView.from(merchant("ONBOARDING", null)).profileConfirmationRequired());
    }

    private static MerchantRow merchant(String source, Instant profileConfirmedAt) {
        Instant now = Instant.now();
        return new MerchantRow(UUID.randomUUID(), "M100000001", UUID.randomUUID(), "merchant", "merchant",
                "OTHER", null, "13900000000", null, null, null, null, null,
                source, profileConfirmedAt, null, null, null, MerchantStatus.ACTIVE,
                false, null, UUID.randomUUID(), 0, now, now);
    }
}
