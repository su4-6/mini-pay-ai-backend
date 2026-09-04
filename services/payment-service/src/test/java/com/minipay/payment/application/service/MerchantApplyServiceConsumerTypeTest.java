package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantApplyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.MerchantApplyStatus;
import com.minipay.payment.domain.model.MerchantType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantApplyServiceConsumerTypeTest {
    private final MerchantApplyStore applies = mock(MerchantApplyStore.class);
    private final MerchantApplyService service = new MerchantApplyService(
            applies,
            mock(MerchantStore.class),
            mock(MerchantManagementService.class),
            mock(IdempotencyStore.class),
            mock(OperationAuditStore.class),
            mock(ObjectMapper.class),
            mock(Clock.class));

    @Test
    void normalizesEveryConsumerSubmissionTypeToIndividual() {
        assertThat(List.of(MerchantType.values()))
                .allSatisfy(type -> assertThat(
                        MerchantApplyService.normalizeConsumerMerchantType(type))
                        .isEqualTo(MerchantType.INDIVIDUAL));
    }

    @Test
    void ownerQueryNormalizesHistoricalTypeWithoutChangingOpsQuery() {
        UUID userId = UUID.randomUUID();
        MerchantApplyStore.ApplyView historical = view(userId, MerchantType.PERSONAL);
        MerchantApplyStore.ApplyPage storedPage = new MerchantApplyStore.ApplyPage(
                List.of(historical), 0, 20, 1);
        when(applies.findPage(0, 20, null, userId)).thenReturn(storedPage);

        MerchantApplyStore.ApplyPage consumerPage = service.listForOwner(userId, 0, 20);
        MerchantApplyStore.ApplyPage opsPage = service.list(0, 20, null, userId);

        assertThat(consumerPage.items()).singleElement()
                .extracting(MerchantApplyStore.ApplyView::merchantType)
                .isEqualTo(MerchantType.INDIVIDUAL.name());
        assertThat(opsPage.items()).singleElement()
                .extracting(MerchantApplyStore.ApplyView::merchantType)
                .isEqualTo(MerchantType.PERSONAL.name());
    }

    private static MerchantApplyStore.ApplyView view(UUID userId, MerchantType type) {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        return new MerchantApplyStore.ApplyView(
                1L, userId, type.name(), "示例店铺", null, "上海市浦东新区",
                java.math.BigDecimal.valueOf(31.23), java.math.BigDecimal.valueOf(121.47),
                "merchant/apply/shop.jpg", "张*", "13800000000", null, null,
                MerchantApplyStatus.PENDING, null, null, null, now, null, 0L, now, now);
    }
}
