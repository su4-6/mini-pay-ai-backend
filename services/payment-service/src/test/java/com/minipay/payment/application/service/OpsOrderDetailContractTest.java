package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OpsOrderDetailContractTest {

    @Test
    void orderDetailsExposeListFieldsAtTheTopLevel() {
        assertFlat(OpsOrderQueryService.PaymentOrderDetailView.class, "paymentOrderNo");
        assertFlat(OpsOrderQueryService.TransferDetailView.class, "transferNo");
        assertFlat(OpsOrderQueryService.RechargeDetailView.class, "rechargeNo");
        assertFlat(OpsOrderQueryService.WithdrawalDetailView.class, "withdrawalNo");
    }

    private static void assertFlat(Class<?> detailType, String orderNumberField) {
        assertThat(Arrays.stream(detailType.getRecordComponents())
                .map(RecordComponent::getName))
                .contains(orderNumberField, "status", "createdAt", "updatedAt")
                .doesNotContain("view");
    }
}
