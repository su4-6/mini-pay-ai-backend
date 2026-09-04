package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.minipay.payment.application.port.ApplicationApplyStore;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.ApplicationApply;
import com.minipay.payment.domain.model.ApplicationApplyStatus;
import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.Merchant;
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
class ApplicationApplyServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T01:00:00Z");
    private static final UUID USER_ID =
            UUID.fromString("019fb3d0-2000-7000-8000-000000000001");
    private static final UUID MERCHANT_ID =
            UUID.fromString("019fb3d0-1000-7000-8000-000000000001");
    private static final UUID APPLICATION_ID =
            UUID.fromString("019fb3d0-1100-7000-8000-000000000001");
    private static final long APPLY_ID = 1001L;
    private static final ObjectMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    @Mock ApplicationApplyStore applies;
    @Mock MerchantStore merchants;
    @Mock ApplicationStore applications;
    @Mock ApplicationManagementService applicationManagement;
    @Mock MerchantService merchantPlatform;
    @Mock IdempotencyStore idempotency;
    @Mock OperationAuditStore audits;
    ApplicationApplyService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationApplyService(
                applies, merchants, applications, applicationManagement,
                merchantPlatform,
                idempotency, audits, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void submitsPendingApplyWithNormalizedName() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applies.findApplyView(anyLong())).thenAnswer(invocation -> Optional.of(
                view(invocation.getArgument(0), ApplicationApplyStatus.PENDING, 0)));

        ApplicationApplyStore.ApplyView created = service.submit(
                MERCHANT_ID, " 星河收银台 ", USER_ID, "idem-submit-application-01", "req-1");

        ArgumentCaptor<ApplicationApply> inserted = ArgumentCaptor.forClass(ApplicationApply.class);
        verify(applies).insert(inserted.capture());
        assertThat(inserted.getValue().name()).isEqualTo("星河收银台");
        assertThat(inserted.getValue().applyStatus()).isEqualTo(ApplicationApplyStatus.PENDING);
        assertThat(created.applyStatus()).isEqualTo(ApplicationApplyStatus.PENDING);
        verify(audits).append(any(), eq(USER_ID.toString()), eq("APPLICATION_APPLY_SUBMIT"),
                eq("APPLICATION_APPLY"), any(), any(), any(), eq("req-1"), eq(NOW));
        verify(idempotency).complete(eq(USER_ID.toString()), eq("application-apply:submit"),
                eq("idem-submit-application-01"), eq(201), anyString(), eq(NOW));
    }

    @Test
    void blocksSubmissionWhenMerchantNotActive() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.DISABLED)));

        assertThatThrownBy(() -> service.submit(
                MERCHANT_ID, "星河收银台", USER_ID, "idem-submit-application-02", "req-2"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_NOT_ACTIVE"));
        verify(applies, never()).insert(any());
    }

    @Test
    void blocksDuplicatePendingSubmission() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applies.existsPendingByMerchant(MERCHANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(
                MERCHANT_ID, "星河收银台", USER_ID, "idem-submit-application-03", "req-3"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPLICATION_APPLY_DUPLICATE_PENDING"));
        verify(applies, never()).insert(any());
    }

    @Test
    void blocksSubmissionWhenApplicationNameTaken() {
        allowIdempotency();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applications.nameExists(MERCHANT_ID, "星河收银台", null)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(
                MERCHANT_ID, "星河收银台", USER_ID, "idem-submit-application-04", "req-4"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPLICATION_APPLY_NAME_CONFLICT"));
        verify(applies, never()).insert(any());
    }

    @Test
    void approveCreatesApplicationAndWritesBackResult() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.PENDING, 3)));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applicationManagement.create(eq(MERCHANT_ID), eq("星河收银台"),
                eq(ApplicationStatus.DISABLED), eq("admin-1"),
                eq("idem-approve-application-01"), eq("req-5")))
                .thenReturn(applicationView());
        when(applies.updateAudit(any(), eq(3L), eq(NOW))).thenReturn(true);
        when(applies.findApplyView(APPLY_ID)).thenReturn(Optional.of(
                view(APPLY_ID, ApplicationApplyStatus.APPROVED, 4)));

        ApplicationApplyStore.ApplyView approved = service.approve(
                APPLY_ID, 3, "admin-1", "idem-approve-application-01", "req-5");

        ArgumentCaptor<ApplicationApply> updated = ArgumentCaptor.forClass(ApplicationApply.class);
        verify(applies).updateAudit(updated.capture(), eq(3L), eq(NOW));
        assertThat(updated.getValue().applyStatus()).isEqualTo(ApplicationApplyStatus.APPROVED);
        assertThat(updated.getValue().resultantApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(approved.applyStatus()).isEqualTo(ApplicationApplyStatus.APPROVED);
        verify(merchantPlatform).provisionApprovedApplication(
                MERCHANT_ID, APPLICATION_ID, applicationView().appId(), "admin-1");
        verify(audits).append(any(), eq("admin-1"), eq("APPLICATION_APPLY_APPROVE"),
                eq("APPLICATION_APPLY"), any(), any(), any(), eq("req-5"), eq(NOW));
        verify(idempotency).complete(eq("admin-1"), eq("application-apply:approve:1001"),
                eq("idem-approve-application-01"), eq(200), anyString(), eq(NOW));
    }

    @Test
    void rejectsApprovalWhenMerchantFrozenAfterSubmission() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.PENDING, 1)));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.FROZEN)));

        assertThatThrownBy(() -> service.approve(
                APPLY_ID, 1, "admin-1", "idem-approve-application-02", "req-6"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MERCHANT_NOT_ACTIVE"));
        verify(applicationManagement, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsApprovalWhenVersionMismatch() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.PENDING, 3)));

        assertThatThrownBy(() -> service.approve(
                APPLY_ID, 2, "admin-1", "idem-approve-application-03", "req-7"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPLICATION_APPLY_VERSION_CONFLICT"));
        verify(applicationManagement, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void blocksAuditOfNonPendingApply() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.APPROVED, 4)));

        assertThatThrownBy(() -> service.approve(
                APPLY_ID, 4, "admin-1", "idem-approve-application-04", "req-8"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPLICATION_APPLY_NOT_PENDING"));
    }

    @Test
    void rejectsPendingApplyWithReason() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.PENDING, 2)));
        when(applies.updateAudit(any(), eq(2L), eq(NOW))).thenReturn(true);
        when(applies.findApplyView(APPLY_ID)).thenReturn(Optional.of(
                view(APPLY_ID, ApplicationApplyStatus.REJECTED, 3)));

        ApplicationApplyStore.ApplyView rejected = service.reject(
                APPLY_ID, "名称含违规词", 2, "admin-1", "idem-reject-application-01", "req-9");

        ArgumentCaptor<ApplicationApply> updated = ArgumentCaptor.forClass(ApplicationApply.class);
        verify(applies).updateAudit(updated.capture(), eq(2L), eq(NOW));
        assertThat(updated.getValue().applyStatus()).isEqualTo(ApplicationApplyStatus.REJECTED);
        assertThat(updated.getValue().rejectReason()).isEqualTo("名称含违规词");
        assertThat(rejected.applyStatus()).isEqualTo(ApplicationApplyStatus.REJECTED);
    }

    @Test
    void resubmitsRejectedApplyBackToPending() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.REJECTED, 2)));
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(applies.updateResubmit(any(), eq(2L), eq(NOW))).thenReturn(true);
        when(applies.findApplyView(APPLY_ID)).thenReturn(Optional.of(
                view(APPLY_ID, ApplicationApplyStatus.PENDING, 3)));

        ApplicationApplyStore.ApplyView resubmitted = service.resubmit(
                APPLY_ID, "星河收银台 Pro", 2, USER_ID, "idem-resubmit-application-01", "req-10");

        ArgumentCaptor<ApplicationApply> updated = ArgumentCaptor.forClass(ApplicationApply.class);
        verify(applies).updateResubmit(updated.capture(), eq(2L), eq(NOW));
        assertThat(updated.getValue().applyStatus()).isEqualTo(ApplicationApplyStatus.PENDING);
        assertThat(updated.getValue().name()).isEqualTo("星河收银台 Pro");
        assertThat(resubmitted.applyStatus()).isEqualTo(ApplicationApplyStatus.PENDING);
    }

    @Test
    void blocksResubmitOfApprovedApply() {
        allowIdempotency();
        when(applies.findApply(APPLY_ID)).thenReturn(Optional.of(apply(ApplicationApplyStatus.APPROVED, 4)));

        assertThatThrownBy(() -> service.resubmit(
                APPLY_ID, "星河收银台 Pro", 4, USER_ID, "idem-resubmit-application-02", "req-11"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPLICATION_APPLY_NOT_RESUBMITTABLE"));
        verify(applies, never()).updateResubmit(any(), any(Long.class), any());
    }

    @Test
    void replaysCompletedApproval() throws Exception {
        ApplicationApplyStore.ApplyView stored = view(
                APPLY_ID, ApplicationApplyStatus.APPROVED, 4);
        when(idempotency.claim(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new IdempotencyStore.Claim(
                        true, invocation.getArgument(4), 200,
                        Optional.of(JSON.writeValueAsString(stored))));

        ApplicationApplyStore.ApplyView approved = service.approve(
                APPLY_ID, 3, "admin-1", "idem-approve-application-05", "req-12");

        assertThat(approved.id()).isEqualTo(APPLY_ID);
        assertThat(approved.applyStatus()).isEqualTo(ApplicationApplyStatus.APPROVED);
        verify(applies, never()).findApply(anyLong());
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

    private static ApplicationApply apply(ApplicationApplyStatus status, long version) {
        return new ApplicationApply(
                APPLY_ID, USER_ID, MERCHANT_ID, "星河收银台", status,
                status == ApplicationApplyStatus.REJECTED
                        || status == ApplicationApplyStatus.SUPPLEMENT ? "原因" : null,
                null, null, NOW.minusSeconds(60),
                status == ApplicationApplyStatus.PENDING ? null : NOW.minusSeconds(30),
                version, NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static ApplicationApplyStore.ApplyView view(
            long id, ApplicationApplyStatus status, long version) {
        return new ApplicationApplyStore.ApplyView(
                id, USER_ID, MERCHANT_ID, "M202608030001", "星河便利店", "星河收银台",
                status, status == ApplicationApplyStatus.REJECTED
                        || status == ApplicationApplyStatus.SUPPLEMENT ? "原因" : null,
                null, null, NOW.minusSeconds(60),
                status == ApplicationApplyStatus.PENDING ? null : NOW.minusSeconds(30),
                version, NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static ApplicationStore.ApplicationView applicationView() {
        return new ApplicationStore.ApplicationView(
                APPLICATION_ID, "mp_app_demo", "星河收银台", MERCHANT_ID,
                "M202608030001", "星河便利店", MerchantStatus.ACTIVE,
                ApplicationStatus.ACTIVE, false, false, null, 0, null,
                NOW, NOW, 0);
    }
}
