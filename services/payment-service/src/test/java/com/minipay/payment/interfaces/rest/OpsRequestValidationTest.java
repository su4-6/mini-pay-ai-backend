package com.minipay.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.payment.domain.model.MerchantType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class OpsRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresVersionForUpdatesAndStatusChanges() {
        assertThat(validator.validate(new OpsController.UpdateMerchantRequest(
                "星河便利店", "星河便利", "张三", "13800000001",
                "demo@example.com", null,
                MerchantType.PERSONAL, null, null, null, null, null, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
        assertThat(validator.validate(new OpsController.VersionRequest(null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
        assertThat(validator.validate(new OpsController.FreezeRequest(null, "原因")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
        assertThat(validator.validate(new OpsController.FreezeRequest(0L, "")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reason");
        assertThat(validator.validate(new OpsController.ApplyRejectRequest(null, "原因")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
        assertThat(validator.validate(new OpsController.UpdateApplicationRequest("应用名称", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
        assertThat(validator.validate(new OpsController.CreateApplicationRequest(
                null, "应用名称", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("merchantId");
        assertThat(validator.validate(new OpsController.SubmitApplicationApplyRequest(
                null, "应用名称")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("merchantId");
        assertThat(validator.validate(new OpsController.ResubmitApplicationApplyRequest(
                "应用名称", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("version");
    }

    @Test
    void validatesImageUploadAndReadRequests() {
        assertThat(validator.validate(new OpsController.ImageUploadRequest(
                "shop.jpg", "image/jpeg", 0, "not-a-sha")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("sizeBytes", "sha256");
        assertThat(validator.validate(new OpsController.ImageReadUrlsRequest(
                java.util.List.of())))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("objectKeys");
    }
}
