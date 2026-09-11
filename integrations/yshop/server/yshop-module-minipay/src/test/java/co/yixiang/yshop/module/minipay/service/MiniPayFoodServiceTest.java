package co.yixiang.yshop.module.minipay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.IdentityView;
import org.junit.jupiter.api.Test;

class MiniPayFoodServiceTest {
    @Test
    void convertsYuanToCentWithoutRounding() {
        assertThat(MiniPayFoodService.toCent(new BigDecimal("12.30"))).isEqualTo(1230);
        assertThat(MiniPayFoodService.toCent(new BigDecimal("0.01"))).isEqualTo(1);
        assertThatThrownBy(() -> MiniPayFoodService.toCent(new BigDecimal("1.001")))
                .isInstanceOf(MiniPayProblem.class)
                .hasMessageContaining("MINIPAY_AMOUNT_PRECISION_INVALID");
    }

    @Test
    void haversineUsesBothDynamicLatitudes() {
        double samePoint = MiniPayFoodService.haversineKm(116.404, 39.915, 116.404, 39.915);
        double oneDegreeNorth = MiniPayFoodService.haversineKm(116.404, 39.915, 116.404, 40.915);
        assertThat(samePoint).isLessThan(0.001);
        assertThat(oneDegreeNorth).isBetween(110.0, 112.0);
    }

    @Test
    void nearbyStoreRequestRequiresExactlyOneLocationSource() {
        MiniPayLocationService.requireExactlyOneSource("019f-location", null);
        MiniPayLocationService.requireExactlyOneSource(null, 42L);
        assertThatThrownBy(() -> MiniPayLocationService.requireExactlyOneSource(null, null))
                .isInstanceOf(MiniPayProblem.class)
                .hasMessageContaining("MINIPAY_LOCATION_SOURCE_INVALID");
        assertThatThrownBy(() ->
                MiniPayLocationService.requireExactlyOneSource("019f-location", 42L))
                .isInstanceOf(MiniPayProblem.class)
                .hasMessageContaining("MINIPAY_LOCATION_SOURCE_INVALID");
    }

    @Test
    void minimumOrderOnlyAppliesToTakeout() {
        assertThat(MiniPayFoodService.meetsMinimumOrder("PICKUP", 1, 1_000)).isTrue();
        assertThat(MiniPayFoodService.meetsMinimumOrder("TAKEOUT", 999, 1_000)).isFalse();
        assertThat(MiniPayFoodService.meetsMinimumOrder("TAKEOUT", 1_000, 1_000)).isTrue();
    }

    @Test
    void normalizesOnlyMainlandMobileNumbersForAccountBinding() {
        assertThat(MiniPayFoodService.normalizedPhone(" 13800138000 "))
                .isEqualTo("13800138000");
        assertThatThrownBy(() -> MiniPayFoodService.normalizedPhone("12345"))
                .isInstanceOf(MiniPayProblem.class)
                .hasMessageContaining("YSHOP_MOBILE_INVALID");
    }

    @Test
    void identityLockHashIsStableAndCompact() {
        assertThat(MiniPayFoodService.shortHash("subject\n13800138000"))
                .hasSize(32)
                .isEqualTo(MiniPayFoodService.shortHash("subject\n13800138000"));
    }

    @Test
    void combinesPersistedOrderDiscountsInCents() {
        assertThat(MiniPayFoodService.orderDiscountCent(
                new BigDecimal("3.20"), new BigDecimal("1.00"))).isEqualTo(420);
        assertThat(MiniPayFoodService.orderDiscountCent(null, null)).isZero();
    }

    @Test
    void identityContractCarriesTheExternalLoginUsername() {
        IdentityView identity = new IdentityView(
                "MINIPAY", "019f0000-0000-7000-8000-000000000001", 42L,
                "external_user", false);

        assertThat(identity.username()).isEqualTo("external_user");
        assertThat(identity.memberId()).isEqualTo(42L);
    }
}
