package com.minipay.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantTest {
    private static final UUID MERCHANT_ID =
            UUID.fromString("019fb3d0-1000-7000-8000-000000000001");

    @Test
    void changesNameAndStatusThroughBusinessMethods() {
        Instant created = Instant.parse("2026-08-03T00:00:00Z");
        Instant changed = created.plusSeconds(60);
        Merchant merchant = new Merchant(
                MERCHANT_ID,
                "M202608030001", "星河便利店", "星河便利", "张三", "13800000001",
                "demo@example.com", null, MerchantType.PERSONAL, "5811", "上海市浦东新区",
                null, null, "https://cdn.example.com/shop/1.jpg", MerchantStatus.ACTIVE,
                null, null, 0, created, created);

        Merchant renamed = merchant.updateProfile(
                "星河社区便利店", "星河便利", "李四", "13900000001",
                "ops@example.com", "已更新", MerchantType.INDIVIDUAL, "5812",
                "上海市徐汇区", null, null, "https://cdn.example.com/shop/2.jpg", changed);
        Merchant disabled = renamed.changeStatus(MerchantStatus.DISABLED, changed.plusSeconds(1));
        Merchant frozen = renamed.freeze("疑似刷单", changed.plusSeconds(2));
        Merchant unfrozen = frozen.unfreeze(changed.plusSeconds(3));

        assertThat(renamed.name()).isEqualTo("星河社区便利店");
        assertThat(renamed.contactName()).isEqualTo("李四");
        assertThat(renamed.merchantType()).isEqualTo(MerchantType.INDIVIDUAL);
        assertThat(renamed.mccCode()).isEqualTo("5812");
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(disabled.status()).isEqualTo(MerchantStatus.DISABLED);
        assertThat(disabled.version()).isEqualTo(2);
        assertThat(frozen.status()).isEqualTo(MerchantStatus.FROZEN);
        assertThat(frozen.freezeReason()).isEqualTo("疑似刷单");
        assertThat(unfrozen.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(unfrozen.freezeReason()).isNull();
    }
}
