package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.Merchant;
import com.minipay.payment.domain.model.MerchantApplication;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T01:00:00Z");
    private static final UUID MERCHANT_ID =
            UUID.fromString("019fb3d0-1000-7000-8000-000000000001");
    private static final UUID APPLICATION_ID =
            UUID.fromString("019fb3d0-1100-7000-8000-000000000001");

    @Mock ApplicationStore applications;
    @Mock MerchantStore merchants;
    @Mock IdempotencyStore idempotency;
    @Mock OperationAuditStore audits;
    ApplicationManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationManagementService(
                applications, merchants, idempotency, audits,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsApplicationWithGeneratedMiniPayAppId() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applications.findApplicationView(any())).thenAnswer(invocation -> Optional.of(
                view(invocation.getArgument(0), ApplicationStatus.ACTIVE, MerchantStatus.ACTIVE, 0)));

        ApplicationStore.ApplicationView created = service.create(
                MERCHANT_ID, " 星河收银台 ", ApplicationStatus.ACTIVE,
                "admin-1", "idem-create-application-01", "req-1");

        ArgumentCaptor<MerchantApplication> inserted = ArgumentCaptor.forClass(MerchantApplication.class);
        verify(applications).insert(inserted.capture());
        assertThat(inserted.getValue().appId()).startsWith("mp_app_").hasSize(39);
        assertThat(inserted.getValue().name()).isEqualTo("星河收银台");
        assertThat(created.status()).isEqualTo(ApplicationStatus.ACTIVE);
        verify(audits).append(any(), eq("admin-1"), eq("APPLICATION_CREATE"),
                eq("APPLICATION"), any(), any(), any(), eq("req-1"), eq(NOW));
        verify(idempotency).complete(eq("admin-1"), eq("application:create"),
                eq("idem-create-application-01"), eq(201), anyString(), eq(NOW));
    }

    @Test
    void allowsOnlyDisabledApplicationForDisabledMerchant() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.DISABLED)));

        assertThatThrownBy(() -> service.create(
                MERCHANT_ID, "云帆收银台", ApplicationStatus.ACTIVE,
                "admin-1", "idem-create-application-02", "req-2"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_NOT_ACTIVE"));
        verify(applications, never()).insert(any());
    }

    @Test
    void rejectsDuplicateNameWithinMerchant() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applications.nameExists(MERCHANT_ID, "星河收银台", null)).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                MERCHANT_ID, "星河收银台", ApplicationStatus.DISABLED,
                "admin-1", "idem-create-application-03", "req-3"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("APPLICATION_NAME_CONFLICT"));
    }

    @Test
    void normalizesIndependentApplicationFilters() {
        service.list(0, 20, " mp_app_01 ", " 收银 ", MERCHANT_ID,
                ApplicationStatus.ACTIVE, null);

        verify(applications).findPage(0, 20, "mp_app_01", "收银", MERCHANT_ID,
                ApplicationStatus.ACTIVE, null);
    }

    @Test
    void passesThroughUnavailableFilter() {
        service.list(0, 20, null, null, null, null, Boolean.TRUE);

        verify(applications).findPage(0, 20, null, null, null, null, Boolean.TRUE);
    }

    @Test
    void returnsPlatformSummary() {
        when(applications.getSummary()).thenReturn(new ApplicationStore.ApplicationSummary(
                4, 3, 1, 1));

        assertThat(service.summary())
                .isEqualTo(new ApplicationStore.ApplicationSummary(4, 3, 1, 1));
    }

    @Test
    void blocksDeletionUntilDisabledAndDependencyFree() {
        allowIdempotency();
        when(applications.findApplication(APPLICATION_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.ACTIVE, 3)));

        assertThatThrownBy(() -> service.delete(
                APPLICATION_ID, 3, "admin-1", "idem-delete-application-01", "req-4"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("APPLICATION_MUST_BE_DISABLED"));

        when(applications.findApplication(APPLICATION_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.DISABLED, 3)));
        when(applications.dependencies(APPLICATION_ID, "mp_app_demo"))
                .thenReturn(new ApplicationStore.Dependencies(1, 0));

        assertThatThrownBy(() -> service.delete(
                APPLICATION_ID, 3, "admin-1", "idem-delete-application-02", "req-5"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("APPLICATION_HAS_TRANSACTIONS"));
        verify(applications, never()).deleteApplication(any(), any(Long.class));
    }

    @Test
    void cannotEnableApplicationForDisabledMerchant() {
        allowIdempotency();
        when(applications.findApplication(APPLICATION_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.DISABLED, 2)));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.DISABLED)));

        assertThatThrownBy(() -> service.changeStatus(
                APPLICATION_ID, ApplicationStatus.ACTIVE, 2,
                "admin-1", "idem-enable-application-01", "req-6"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_NOT_ACTIVE"));
        verify(applications, never()).updateStatus(any(), any(), any(Long.class), any());
    }

    private void allowIdempotency() {
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), null, Optional.empty()));
    }

    private static Merchant merchant(MerchantStatus status) {
        return new Merchant(MERCHANT_ID, "M202608030001", "星河便利店", "星河便利", "张三",
                "13800000001", null, null, MerchantType.PERSONAL, null, null,
                null, null, null, status, null, null, 0,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static MerchantApplication application(ApplicationStatus status, long version) {
        return new MerchantApplication(APPLICATION_ID, "mp_app_demo", MERCHANT_ID,
                "星河收银台", status, version, NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static ApplicationStore.ApplicationView view(
            UUID applicationId,
            ApplicationStatus status,
            MerchantStatus merchantStatus,
            long version) {
        return new ApplicationStore.ApplicationView(
                applicationId, "mp_app_" + applicationId.toString().replace("-", ""),
                "星河收银台", MERCHANT_ID, "M202608030001", "星河便利店", merchantStatus,
                status, false, false, "APPLICATION_MUST_BE_DISABLED", 0, null, NOW, NOW, version);
    }
}
