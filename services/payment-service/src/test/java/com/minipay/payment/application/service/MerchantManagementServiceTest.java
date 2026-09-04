package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.Merchant;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import com.minipay.payment.infrastructure.client.IdentityServiceClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MerchantManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T03:00:00Z");
    private static final UUID MERCHANT_ID =
            UUID.fromString("019fb3d0-1000-7000-8000-000000000001");
    private static final UUID OWNER_ID =
            UUID.fromString("019fb3d0-2000-7000-8000-000000000001");

    @Mock MerchantStore merchants;
    @Mock IdempotencyStore idempotency;
    @Mock OperationAuditStore audits;
    @Mock IdentityServiceClient identity;
    @Mock MerchantWalletProvisioner walletProvisioner;
    MerchantManagementService service;

    @BeforeEach
    void setUp() {
        service = new MerchantManagementService(
                merchants,
                idempotency,
                audits,
                identity,
                walletProvisioner,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnActiveMerchantAndCompletesIdempotencyInTheSameUseCase() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), null, Optional.empty()));
        when(identity.resolveMerchantOwner(anyString(), anyString(), anyString()))
                .thenReturn(new IdentityServiceClient.MerchantOwner(OWNER_ID, "consumer_1", false));
        MerchantStore.MerchantView view = view(0);
        when(merchants.findView(any())).thenReturn(Optional.of(view));

        MerchantStore.MerchantView created = service.create(
                " 星河便利店 ", " 星河便利 ", " 张三 ", "13800000001",
                " demo@example.com ", " 演示商户 ", null, null, null, null, null, null, null,
                "admin-1", "idem-create-merchant-01", "req-1");

        assertThat(created.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(created.profileComplete()).isTrue();
        verify(identity).resolveMerchantOwner(eq("13800000001"), eq("张三"), eq("req-1"));
        verify(merchants).insert(any(Merchant.class));
        verify(audits).append(
                any(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), any());
        verify(idempotency).complete(
                eq("admin-1"), eq("merchant:create"), eq("idem-create-merchant-01"),
                eq(201), anyString(), eq(NOW));
    }

    @Test
    void rejectsAReusedKeyWithDifferentRequestContent() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyStore.Claim(
                        false, "different-digest", 201, Optional.of("{}")));

        assertThatThrownBy(() -> service.create(
                "星河便利店", "星河便利", "张三", "13800000001",
                null, null, MerchantType.PERSONAL, null, null, null, null, null,
                MerchantStatus.ACTIVE, "admin-1", "idem-create-merchant-01", "req-1"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(merchants, never()).insert(any());
    }

    @Test
    void normalizesIndependentMerchantNumberAndNameFilters() {
        service.list(0, 20, " M2026 ", " 星河 ", " 13800000001 ", MerchantStatus.ACTIVE);

        verify(merchants).findPage(0, 20, "M2026", "星河", "13800000001",
                MerchantStatus.ACTIVE);
    }

    @Test
    void rejectsOutOfRangeCoordinatesBeforeClaimingIdempotency() {
        assertThatThrownBy(() -> service.create(
                "星河便利店", "星河便利", "张三", "13800000001",
                null, null, MerchantType.PERSONAL, null, null,
                BigDecimal.valueOf(91), BigDecimal.ZERO, null,
                MerchantStatus.ACTIVE, "admin-1", "idem-create-merchant-03", "req-3"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_LATITUDE"));
        assertThatThrownBy(() -> service.create(
                "星河便利店", "星河便利", "张三", "13800000001",
                null, null, MerchantType.PERSONAL, null, null,
                BigDecimal.ZERO, BigDecimal.valueOf(181), null,
                MerchantStatus.ACTIVE, "admin-1", "idem-create-merchant-04", "req-4"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_LONGITUDE"));
        verify(idempotency, never()).claim(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsInvalidContactDetailsBeforeClaimingIdempotency() {
        assertThatThrownBy(() -> service.create(
                "星河便利店", "星河便利", "张三", "12345",
                "not-an-email", null, MerchantType.PERSONAL, null, null, null, null, null,
                MerchantStatus.ACTIVE,
                "admin-1", "idem-create-merchant-02", "req-2"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_CONTACT_MOBILE"));
        verify(idempotency, never()).claim(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void refusesToDeleteAMerchantWithDependencies() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), null, Optional.empty()));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(3)));
        when(merchants.dependencies(MERCHANT_ID)).thenReturn(
                new MerchantStore.Dependencies(1, 0));

        assertThatThrownBy(() -> service.delete(
                MERCHANT_ID, 3, "admin-1", "idem-delete-merchant-01", "req-1"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_HAS_DEPENDENCIES"));
        verify(merchants, never()).delete(any(), any(Long.class));
    }

    @Test
    void freezesAndUnfreezesAnActiveMerchant() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), null, Optional.empty()));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(0)));
        when(merchants.updateFreeze(eq(MERCHANT_ID), eq(MerchantStatus.FROZEN), eq("疑似刷单"),
                eq(0L), any())).thenReturn(true);
        MerchantStore.MerchantView frozenView = viewFrozen(1);
        when(merchants.findView(MERCHANT_ID)).thenReturn(Optional.of(frozenView));

        MerchantStore.MerchantView frozen = service.freeze(
                MERCHANT_ID, "疑似刷单", 0, "admin-1", "idem-freeze-merchant-01", "req-3");

        assertThat(frozen.status()).isEqualTo(MerchantStatus.FROZEN);
        assertThat(frozen.freezeReason()).isEqualTo("疑似刷单");
        verify(audits).append(any(), eq("admin-1"), eq("MERCHANT_FREEZE"),
                eq("MERCHANT"), eq(MERCHANT_ID), any(), any(), eq("req-3"), eq(NOW));

        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchantFrozen(1)));
        when(merchants.updateFreeze(eq(MERCHANT_ID), eq(MerchantStatus.ACTIVE), isNull(),
                eq(1L), any())).thenReturn(true);
        when(merchants.findView(MERCHANT_ID)).thenReturn(Optional.of(view(2)));

        MerchantStore.MerchantView unfrozen = service.unfreeze(
                MERCHANT_ID, 1, "admin-1", "idem-unfreeze-merchant-01", "req-4");

        assertThat(unfrozen.status()).isEqualTo(MerchantStatus.ACTIVE);
        verify(audits).append(any(), eq("admin-1"), eq("MERCHANT_UNFREEZE"),
                eq("MERCHANT"), eq(MERCHANT_ID), any(), any(), eq("req-4"), eq(NOW));
    }

    @Test
    void refusesToFreezeADisabledMerchant() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), null, Optional.empty()));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchantDisabled(2)));

        assertThatThrownBy(() -> service.freeze(
                MERCHANT_ID, "疑似刷单", 2, "admin-1", "idem-freeze-merchant-02", "req-5"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_NOT_FREEZABLE"));
        verify(merchants, never()).updateFreeze(any(), any(), any(), anyLong(), any());
    }

    private static Merchant merchant(long version) {
        return new Merchant(
                MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", "demo@example.com", null, MerchantType.PERSONAL, null, null,
                null, null, null, MerchantStatus.ACTIVE, null, OWNER_ID, version,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static Merchant merchantDisabled(long version) {
        return new Merchant(
                MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", "demo@example.com", null, MerchantType.PERSONAL, null, null,
                null, null, null, MerchantStatus.DISABLED, null, OWNER_ID, version,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static Merchant merchantFrozen(long version) {
        return new Merchant(
                MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", "demo@example.com", null, MerchantType.PERSONAL, null, null,
                null, null, null, MerchantStatus.FROZEN, "疑似刷单", OWNER_ID, version,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static MerchantStore.MerchantView view(long version) {
        return new MerchantStore.MerchantView(
                MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", "demo@example.com", null, MerchantType.PERSONAL, null,
                null, null, null, null, true, MerchantStatus.ACTIVE, null, true, OWNER_ID,
                0, true, null, NOW, NOW, version);
    }

    private static MerchantStore.MerchantView viewFrozen(long version) {
        return new MerchantStore.MerchantView(
                MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", "demo@example.com", null, MerchantType.PERSONAL, null,
                null, null, null, null, true, MerchantStatus.FROZEN, "疑似刷单", true, OWNER_ID,
                0, true, null, NOW, NOW, version);
    }
}
