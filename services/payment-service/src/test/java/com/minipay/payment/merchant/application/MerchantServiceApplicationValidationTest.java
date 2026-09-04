package com.minipay.payment.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantRow;
import com.minipay.payment.infrastructure.security.MerchantSecretCipher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MerchantServiceApplicationValidationTest {
    private final MerchantRepository repository = mock(MerchantRepository.class);
    private MerchantService service;

    @BeforeEach
    void setUp() {
        UUID ownerId = UUID.randomUUID();
        MerchantRow merchant = new MerchantRow(UUID.randomUUID(), "M100000001", ownerId, "测试商户", "测试商户",
                "餐饮", null, null, null, null, null, null, null,
                "ONBOARDING", null, null, "v4.7.0", Instant.now(), MerchantStatus.ACTIVE,
                false, null, UUID.randomUUID(), 0, Instant.now(), Instant.now());
        when(repository.findByOwner(ownerId)).thenReturn(Optional.of(merchant));
        service = new MerchantService(repository,
                new MerchantSecretCipher("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
                new MerchantContentSafety(), mock(RefundService.class));
        this.ownerId = ownerId;
    }

    private UUID ownerId;

    @Test
    void rejectsApplicationNameLongerThanFortyCharacters() {
        PaymentProblemException problem = assertThrows(PaymentProblemException.class,
                () -> service.createApplication(ownerId, input("a".repeat(41), "https://merchant.example/notify")));
        assertEquals("INVALID_APPLICATION_NAME", problem.code());
    }

    @Test
    void requiresPaymentNotificationUrlForMerchantCreatedApplications() {
        PaymentProblemException problem = assertThrows(PaymentProblemException.class,
                () -> service.createApplication(ownerId, input("门店收银台", null)));
        assertEquals("NOTIFY_URL_REQUIRED", problem.code());
    }

    @Test
    void rejectsNonHttpsPaymentNotificationUrl() {
        PaymentProblemException problem = assertThrows(PaymentProblemException.class,
                () -> service.createApplication(ownerId, input("门店收银台", "http://merchant.example/notify")));
        assertEquals("INVALID_NOTIFY_URL", problem.code());
    }

    private static MerchantService.ApplicationInput input(String name, String notifyUrl) {
        return new MerchantService.ApplicationInput(name, notifyUrl, null, List.of(),
                List.of("PAYMENT_CREATE", "PAYMENT_QUERY"), List.of("WALLET"));
    }
}
