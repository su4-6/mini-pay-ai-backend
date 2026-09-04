package com.minipay.identity.infrastructure.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.minipay.identity.application.port.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云号码认证服务（Phone Number Verification Service）。
 * 验证码由阿里云生成并下发（SendSmsVerifyCode + returnVerifyCode=true），
 * 本服务只拿到云端返回的验证码明文用于 HMAC 摘要落库，不持久化明文。
 * 与 {@link AliyunSmsSender}（标准短信 dysmsapi）互斥，provider=dypns 时激活。
 */
@Component
@ConditionalOnProperty(name = "minipay.identity.sms.provider", havingValue = "dypns")
public final class DypnsSmsSender implements SmsSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(DypnsSmsSender.class);
    private static final String SUCCESS_CODE = "OK";

    private final Client client;
    private final String signName;
    private final String templateCode;
    private final String templateParam;
    private final long validTimeSeconds;
    private final long sendIntervalSeconds;

    @Autowired
    public DypnsSmsSender(
            @Value("${minipay.identity.sms.dypns.endpoint}") String endpoint,
            @Value("${minipay.identity.sms.dypns.sign-name}") String signName,
            @Value("${minipay.identity.sms.dypns.template-code}") String templateCode,
            @Value("${minipay.identity.sms.dypns.template-param}") String templateParam,
            @Value("${minipay.identity.sms.dypns.valid-time-seconds}") long validTimeSeconds,
            @Value("${minipay.identity.sms.dypns.send-interval-seconds}") long sendIntervalSeconds,
            @Value("${minipay.identity.sms.dypns.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${minipay.identity.sms.dypns.read-timeout-ms}") int readTimeoutMs,
            @Value("${minipay.identity.sms.dypns.access-key-id}") String accessKeyId,
            @Value("${minipay.identity.sms.dypns.access-key-secret}") String accessKeySecret)
            throws Exception {
        this(
                createClient(
                        endpoint,
                        connectTimeoutMs,
                        readTimeoutMs,
                        accessKeyId,
                        accessKeySecret),
                signName,
                templateCode,
                templateParam,
                validTimeSeconds,
                sendIntervalSeconds);
    }

    DypnsSmsSender(
            Client client,
            String signName,
            String templateCode,
            String templateParam,
            long validTimeSeconds,
            long sendIntervalSeconds) {
        this.client = client;
        this.signName = required(signName, "sign-name");
        this.templateCode = required(templateCode, "template-code");
        this.templateParam = required(templateParam, "template-param");
        if (!templateParam.contains("##code##")) {
            throw new IllegalStateException(
                    "Alibaba Cloud dypns template-param must contain the ##code## placeholder");
        }
        if (validTimeSeconds <= 0 || sendIntervalSeconds <= 0) {
            throw new IllegalStateException(
                    "Alibaba Cloud dypns valid-time and send-interval must be positive");
        }
        this.validTimeSeconds = validTimeSeconds;
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    @Override
    public String sendAndGetLoginCode(String normalizedPhone) {
        return sendAndGetCode(normalizedPhone);
    }

    @Override
    public String sendAndGetConsumerLoginCode(String normalizedPhone) {
        return sendAndGetCode(normalizedPhone);
    }

    /**
     * 号码认证的"下发"即"生成"：短信由阿里云直接发出，调用方无法指定验证码。
     * 因此本渠道不支持 sendLoginCode 下发指定码，误用即报错。
     */
    @Override
    public void sendLoginCode(String normalizedPhone, String code) {
        throw new SmsDeliveryUnavailableException();
    }

    private String sendAndGetCode(String normalizedPhone) {
        SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                .setCountryCode("86")
                .setPhoneNumber(normalizedPhone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam(templateParam)
                .setCodeType(1L)
                .setCodeLength(6L)
                .setValidTime(validTimeSeconds)
                .setInterval(sendIntervalSeconds)
                .setDuplicatePolicy(1L)
                .setReturnVerifyCode(true);
        try {
            SendSmsVerifyCodeResponseBody body = client.sendSmsVerifyCode(request).getBody();
            if (!isSuccessful(body == null ? null : body.getSuccess(), body == null ? null : body.getCode())) {
                String providerCode = body == null ? "EMPTY_RESPONSE" : safe(body.getCode());
                String requestId = body == null ? "unknown" : safe(body.getRequestId());
                LOGGER.warn("Alibaba Cloud dypns rejected send: providerCode={}, requestId={}",
                        providerCode, requestId);
                throw new SmsDeliveryUnavailableException();
            }
            String verifyCode = body == null || body.getModel() == null
                    ? null
                    : body.getModel().getVerifyCode();
            if (verifyCode == null || verifyCode.isBlank()) {
                LOGGER.warn("Alibaba Cloud dypns returned no verify code: requestId={}",
                        body == null ? "unknown" : safe(body.getRequestId()));
                throw new SmsDeliveryUnavailableException();
            }
            return verifyCode;
        } catch (SmsDeliveryUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Alibaba Cloud dypns invocation failed: exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new SmsDeliveryUnavailableException(exception);
        }
    }

    private static boolean isSuccessful(Boolean success, String code) {
        return Boolean.TRUE.equals(success) && SUCCESS_CODE.equals(code);
    }

    private static Client createClient(
            String endpoint,
            int connectTimeoutMs,
            int readTimeoutMs,
            String accessKeyId,
            String accessKeySecret) throws Exception {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("Alibaba Cloud dypns timeouts must be positive");
        }
        Config config = new Config()
                .setAccessKeyId(required(accessKeyId, "access-key-id"))
                .setAccessKeySecret(required(accessKeySecret, "access-key-secret"))
                .setEndpoint(required(endpoint, "endpoint"))
                .setConnectTimeout(connectTimeoutMs)
                .setReadTimeout(readTimeoutMs);
        return new Client(config);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Alibaba Cloud dypns " + name + " is required");
        }
        return value.trim();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
