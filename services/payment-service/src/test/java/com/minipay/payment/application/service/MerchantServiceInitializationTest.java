package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.CollectionCodeRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.CreatedDefaultApplication;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantApplicationRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantRow;
import com.minipay.payment.infrastructure.security.MerchantSecretCipher;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantServiceInitializationTest {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void initializesApprovedMerchantOnceAndAlwaysReturnsTheSameEnabledQrContent() {
        MerchantRepository repository = mock(MerchantRepository.class);
        UUID ownerId = UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
        UUID merchantId = UUID.fromString("019fb3d0-2000-7000-8000-000000000002");
        UUID applicationId = UUID.fromString("019fb3d0-2000-7000-8000-000000000003");
        UUID codeId = UUID.fromString("019fb3d0-2000-7000-8000-000000000004");
        Instant now = Instant.parse("2026-08-09T08:00:00Z");
        MerchantRow merchant = new MerchantRow(
                merchantId, "M202608090001", ownerId, "测试商户", "测试商户", "其他",
                "张*", "138****0000", null, "河南省洛阳市", null, null, "shop/test.jpg",
                "ONBOARDING", now, UUID.randomUUID(), "v1", now, MerchantStatus.ACTIVE,
                false, null, null, 0, now, now);
        MerchantRow initializedMerchant = new MerchantRow(
                merchantId, merchant.merchantNo(), ownerId, merchant.name(), merchant.shortName(),
                merchant.category(), merchant.contactName(), merchant.contactMobile(), null,
                merchant.address(), null, null, merchant.shopImages(), merchant.source(), now,
                merchant.onboardingId(), merchant.agreementVersion(), now, MerchantStatus.ACTIVE,
                false, null, applicationId, 1, now, now);
        MerchantApplicationRow application = new MerchantApplicationRow(
                applicationId, merchantId, "mp_app_test", "默认应用", "SELF_USE", true,
                null, null, null, "PAYMENT_CREATE,PAYMENT_QUERY,REFUND_CREATE",
                "WALLET,ALIPAY,WECHAT", "ACTIVE", false, 0, now, now);
        CollectionCodeRow code = new CollectionCodeRow(
                codeId, applicationId, "mc_stable_token", "ENABLED", 1, 0);

        when(repository.findOwnedForUpdate(ownerId, merchantId)).thenReturn(Optional.of(merchant));
        when(repository.findDefaultApplication(merchantId))
                .thenReturn(Optional.empty(), Optional.of(application));
        when(repository.createDefaultApplication(eq(merchantId), any(byte[].class)))
                .thenReturn(new CreatedDefaultApplication(application, code));
        when(repository.findDefaultCode(merchantId)).thenReturn(Optional.of(code));
        when(repository.findOwned(ownerId, merchantId)).thenReturn(Optional.of(initializedMerchant));

        MerchantService service = new MerchantService(
                repository, new MerchantSecretCipher(KEY), new MerchantContentSafety(),
                mock(RefundService.class));

        MerchantService.InitializationView first = service.initialize(ownerId, merchantId);
        MerchantService.InitializationView replay = service.initialize(ownerId, merchantId);

        assertThat(first.collectionCode().status()).isEqualTo("ENABLED");
        assertThat(first.qrContent()).isEqualTo("minipay://collect/merchant?token=mc_stable_token");
        assertThat(replay.qrContent()).isEqualTo(first.qrContent());
        verify(repository).createDefaultApplication(eq(merchantId), any(byte[].class));
        verify(repository, never()).createCollectionCode(applicationId);
    }
}
