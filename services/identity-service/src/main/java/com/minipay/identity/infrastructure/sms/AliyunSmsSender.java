package com.minipay.identity.infrastructure.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.port.SmsSender;
import com.minipay.identity.infrastructure.aliyun.AliyunCredentialsFactory;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.identity.sms.provider", havingValue = "aliyun")
public final class AliyunSmsSender implements SmsSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(AliyunSmsSender.class);
    private static final String SUCCESS_CODE = "OK";

    private final SmsGateway gateway;
    private final ObjectMapper objectMapper;
    private final String signName;
    private final String loginTemplateCode;

    @Autowired
    public AliyunSmsSender(
            ObjectMapper objectMapper,
            @Value("${minipay.identity.sms.aliyun.endpoint}") String endpoint,
            @Value("${minipay.identity.sms.aliyun.region}") String region,
            @Value("${minipay.identity.sms.aliyun.sign-name}") String signName,
            @Value("${minipay.identity.sms.aliyun.login-template-code}") String loginTemplateCode,
            @Value("${minipay.identity.sms.aliyun.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${minipay.identity.sms.aliyun.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${minipay.identity.sms.aliyun.ram-role-name:}") String ramRoleName,
            @Value("${minipay.identity.sms.aliyun.access-key-id:}") String accessKeyId,
            @Value("${minipay.identity.sms.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${minipay.identity.sms.aliyun.security-token:}") String securityToken)
            throws Exception {
        this(
                createGateway(
                        endpoint,
                        region,
                        connectTimeoutMs,
                        readTimeoutMs,
                        ramRoleName,
                        accessKeyId,
                        accessKeySecret,
                        securityToken),
                objectMapper,
                signName,
                loginTemplateCode);
    }

    AliyunSmsSender(
            SmsGateway gateway,
            ObjectMapper objectMapper,
            String signName,
            String loginTemplateCode) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.signName = required(signName, "sign-name");
        this.loginTemplateCode = required(loginTemplateCode, "login-template-code");
    }

    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        send(normalizedPhone, code);
    }

    @Override
    public void sendConsumerLoginCode(String normalizedPhone, String code) {
        send(normalizedPhone, code);
    }

    private void send(String normalizedPhone, String code) {
        try {
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(normalizedPhone)
                    .setSignName(signName)
                    .setTemplateCode(loginTemplateCode)
                    .setTemplateParam(templateParameters(code));
            SendSmsResponseBody body = gateway.send(request);
            if (body == null || !SUCCESS_CODE.equalsIgnoreCase(body.getCode())) {
                String providerCode = body == null ? "EMPTY_RESPONSE" : safe(body.getCode());
                String requestId = body == null ? "unknown" : safe(body.getRequestId());
                LOGGER.warn("Alibaba Cloud SMS rejected request: providerCode={}, requestId={}",
                        providerCode, requestId);
                throw new SmsDeliveryUnavailableException();
            }
        } catch (SmsDeliveryUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Alibaba Cloud SMS invocation failed: exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new SmsDeliveryUnavailableException(exception);
        }
    }

    private String templateParameters(String code) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of("code", code));
    }

    private static SmsGateway createGateway(
            String endpoint,
            String region,
            int connectTimeoutMs,
            int readTimeoutMs,
            String ramRoleName,
            String accessKeyId,
            String accessKeySecret,
            String securityToken) throws Exception {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("Alibaba Cloud SMS timeouts must be positive");
        }
        Config config = new Config()
                .setCredential(AliyunCredentialsFactory.create(
                        ramRoleName, accessKeyId, accessKeySecret, securityToken))
                .setEndpoint(required(endpoint, "endpoint"))
                .setRegionId(required(region, "region"))
                .setConnectTimeout(connectTimeoutMs)
                .setReadTimeout(readTimeoutMs);
        Client client = new Client(config);
        return request -> {
            SendSmsResponse response = client.sendSms(request);
            return response == null ? null : response.getBody();
        };
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Alibaba Cloud SMS " + name + " is required");
        }
        return value.trim();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    @FunctionalInterface
    interface SmsGateway {
        SendSmsResponseBody send(SendSmsRequest request) throws Exception;
    }
}
