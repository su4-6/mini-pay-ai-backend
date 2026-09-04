package com.minipay.identity.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AliyunSmsSenderTest {
    @Test
    void mapsLoginCodeToApprovedTemplateParameters() {
        AtomicReference<SendSmsRequest> captured = new AtomicReference<>();
        AliyunSmsSender sender = new AliyunSmsSender(
                request -> {
                    captured.set(request);
                    return new SendSmsResponseBody()
                            .setCode("OK")
                            .setRequestId("request-1")
                            .setBizId("biz-1");
                },
                new ObjectMapper(),
                "MiniPay",
                "SMS_123456789");

        sender.sendConsumerLoginCode("13800138000", "482915");

        assertThat(captured.get().getPhoneNumbers()).isEqualTo("13800138000");
        assertThat(captured.get().getSignName()).isEqualTo("MiniPay");
        assertThat(captured.get().getTemplateCode()).isEqualTo("SMS_123456789");
        assertThat(captured.get().getTemplateParam()).isEqualTo("{\"code\":\"482915\"}");
    }

    @Test
    void rejectsProviderFailureWithStableApplicationException() {
        AliyunSmsSender sender = new AliyunSmsSender(
                request -> new SendSmsResponseBody()
                        .setCode("isv.SMS_TEMPLATE_ILLEGAL")
                        .setRequestId("request-2"),
                new ObjectMapper(),
                "MiniPay",
                "SMS_123456789");

        assertThatThrownBy(() -> sender.sendLoginCode("13800138000", "482915"))
                .isInstanceOf(SmsDeliveryUnavailableException.class)
                .hasMessage("SMS delivery is temporarily unavailable");
    }

    @Test
    void rejectsIncompleteChannelConfigurationAtStartup() {
        assertThatThrownBy(() -> new AliyunSmsSender(
                request -> new SendSmsResponseBody().setCode("OK"),
                new ObjectMapper(),
                "",
                "SMS_123456789"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sign-name");
    }
}
