package com.minipay.consumerbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveFieldMaskerTest {

    @Test
    void masksPaymentPasswordsAndOAuthTokens() {
        String redacted = SensitiveFieldMasker.redact(
                "{\"payPassword\":\"123456\",\"paymentPassword\":\"654321\","
                        + "\"access_token\":\"eyJhbGciOiJSUzI1NiJ9.payload.signature\","
                        + "\"refresh_token\":\"opaque-refresh\","
                        + "\"paymentAuthToken\":\"one-time\","
                        + "\"Authorization\":\"Bearer abc.def.ghi\"}");

        assertThat(redacted).doesNotContain("123456");
        assertThat(redacted).doesNotContain("654321");
        assertThat(redacted).doesNotContain("opaque-refresh");
        assertThat(redacted).doesNotContain("one-time");
        assertThat(redacted).doesNotContain("abc.def.ghi");
        assertThat(redacted).contains("\"payPassword\":\"***\"");
    }

    @Test
    void masksUnquotedFormFieldsOfThePaymentAuthorizationRequest() {
        String redacted = SensitiveFieldMasker.redact(
                "payPassword=123456&paymentPassword=654321&access_token=abc.def.ghi");

        assertThat(redacted).doesNotContain("123456");
        assertThat(redacted).doesNotContain("654321");
        assertThat(redacted).doesNotContain("abc.def.ghi");
    }

    @Test
    void masksSixDigitSmsCodesButKeepsBusinessCodesReadable() {
        String redacted = SensitiveFieldMasker.redact(
                "{\"code\":\"123456\",\"code\":\"SMS_CODE_INVALID\"}");

        assertThat(redacted).doesNotContain("123456");
        assertThat(redacted).contains("SMS_CODE_INVALID");
    }

    @Test
    void masksFullMobileNumbersButKeepsMaskedOnes() {
        String redacted = SensitiveFieldMasker.redact(
                "{\"mobile\":\"13800138000\",\"phone\":\"138****8000\"}");

        assertThat(redacted).doesNotContain("13800138000");
        assertThat(redacted).contains("138****8000");
    }

    @Test
    void masksBareMobileNumbersOutsideJson() {
        String redacted = SensitiveFieldMasker.redact("lookup failed for 13800138000 now");

        assertThat(redacted).doesNotContain("13800138000");
        assertThat(redacted).contains("138****8000");
    }

    @Test
    void masksCredentialHeadersWhenRenderingDiagnostics() {
        String rendered = SensitiveFieldMasker.redactHeaders(java.util.Map.of(
                "Authorization", java.util.List.of("Bearer secret"),
                "Cookie", java.util.List.of("SESSION=abc"),
                "X-Request-Id", java.util.List.of("request-1"),
                "Accept", java.util.List.of("application/json")));

        assertThat(rendered).doesNotContain("Bearer secret");
        assertThat(rendered).doesNotContain("SESSION=abc");
        assertThat(rendered).doesNotContain("request-1");
        assertThat(rendered).contains("application/json");
    }

    @Test
    void masksEveryAdjacentSecretBeforeAndAfterNonSecretFields() {
        String redacted = SensitiveFieldMasker.redact(
                "{\"payPassword\":\"111111\",\"paymentPassword\":\"222222\","
                        + "\"newPassword\":\"333333\",\"codeVerifier\":\"verifier-value\","
                        + "\"access_token\":\"aaa.bbb.ccc\",\"refreshToken\":\"ddd.eee.fff\","
                        + "\"paymentAuthToken\":\"ggg.hhh.iii\","
                        + "\"Authorization\":\"Bearer jjj.kkk.lll\","
                        + "\"status\":\"SUCCEEDED\",\"amountFen\":2500,"
                        + "\"code\":\"123456\",\"mobile\":\"13800138000\"}");

        assertThat(redacted)
                .doesNotContain("111111")
                .doesNotContain("222222")
                .doesNotContain("333333")
                .doesNotContain("verifier-value")
                .doesNotContain("aaa.bbb.ccc")
                .doesNotContain("ddd.eee.fff")
                .doesNotContain("ggg.hhh.iii")
                .doesNotContain("jjj.kkk.lll")
                .doesNotContain("123456")
                .doesNotContain("13800138000")
                // Non-secret neighbours must stay readable for diagnostics.
                .contains("\"status\":\"SUCCEEDED\"")
                .contains("\"amountFen\":2500");
    }

    @Test
    void masksSecretsThatFollowANonSecretField() {
        String redacted = SensitiveFieldMasker.redact(
                "{\"status\":\"PENDING\",\"payPassword\":\"123456\","
                        + "\"amountFen\":100,\"paymentAuthToken\":\"one-time\"}");

        assertThat(redacted)
                .doesNotContain("123456")
                .doesNotContain("one-time")
                .contains("\"status\":\"PENDING\"")
                .contains("\"amountFen\":100");
    }

    @Test
    void leavesInnocentContentUntouched() {
        assertThat(SensitiveFieldMasker.redact("{\"status\":\"SUCCEEDED\",\"amountFen\":2500}"))
                .isEqualTo("{\"status\":\"SUCCEEDED\",\"amountFen\":2500}");
    }
}
