package com.minipay.commerce.infrastructure.yshop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class YshopOrderContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsOrderPresentationSnapshotWithoutChangingMoneyUnits() throws Exception {
        YshopModels.Order order = json.readValue("""
                {
                  "orderRefId":"019f0000-0000-7000-8000-000000000001",
                  "externalOrderNo":"YS20260809001",
                  "shopId":2,
                  "shopName":"洛阳测试餐厅",
                  "amountCent":1590,
                  "currency":"CNY",
                  "paymentStatus":"PAID",
                  "fulfillmentStatus":"PREPARING",
                  "refundStatus":"NONE",
                  "fulfillmentType":"TAKEOUT",
                  "createdAt":"2026-08-09T10:00:00Z",
                  "expiresAt":"2026-08-09T10:15:00Z",
                  "items":[{"productId":9,"name":"招牌套餐","sku":"默认","image":"/img/meal.jpg","quantity":1,"unitPriceCent":1390,"lineAmountCent":1390}],
                  "totalQuantity":1,
                  "subtotalCent":1390,
                  "deliveryFeeCent":200,
                  "discountCent":0
                }
                """, YshopModels.Order.class);

        assertThat(order.externalOrderNo()).isEqualTo("YS20260809001");
        assertThat(order.shopName()).isEqualTo("洛阳测试餐厅");
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("招牌套餐");
            assertThat(item.lineAmountCent()).isEqualTo(1390);
        });
        assertThat(order.subtotalCent() + order.deliveryFeeCent() - order.discountCent())
                .isEqualTo(order.amountCent());
    }
}
