package com.minipay.identity.infrastructure.realname;

import com.aliyun.teaopenapi.Client;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.models.RuntimeOptions;
import com.minipay.identity.application.port.RealNameVerificationPort;
import com.minipay.identity.application.service.RealNameVerificationRejectedException;
import com.minipay.identity.infrastructure.aliyun.AliyunCredentialsFactory;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.identity.real-name.provider", havingValue = "aliyun")
public final class AliyunRealNameVerification implements RealNameVerificationPort {
    private final Client client;

    public AliyunRealNameVerification(
            @Value("${minipay.identity.real-name.endpoint}") String endpoint,
            @Value("${minipay.identity.real-name.region}") String region,
            @Value("${minipay.identity.real-name.ram-role-name}") String ramRoleName,
            @Value("${minipay.identity.real-name.access-key-id}") String accessKeyId,
            @Value("${minipay.identity.real-name.access-key-secret}") String accessKeySecret,
            @Value("${minipay.identity.real-name.security-token:}") String securityToken)
            throws Exception {
        Config config = new Config()
                .setEndpoint(endpoint)
                .setRegionId(region)
                .setCredential(AliyunCredentialsFactory.create(
                        ramRoleName, accessKeyId, accessKeySecret, securityToken));
        this.client = new Client(config);
    }

    @Override
    public VerificationResult verify(String legalName, String idNumber, byte[] faceJpeg) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("certificateName", legalName);
            body.put("certificateNumber", idNumber);
            body.put("facialPictureData", Base64.getEncoder().encodeToString(faceJpeg));
            body.put("sceneType", "server");
            Params params = new Params()
                    .setAction("ExecuteServerSideVerification")
                    .setVersion("2019-12-30")
                    .setProtocol("HTTPS")
                    .setPathname("/viapi/thirdparty/realperson/execServerSideVerification")
                    .setMethod("POST")
                    .setAuthType("AK")
                    .setStyle("ROA")
                    .setReqBodyType("formData")
                    .setBodyType("json");
            Map<String, ?> response = client.callApi(
                    params, new OpenApiRequest().setBody(body), new RuntimeOptions());
            Object rawBody = response.get("body");
            if (!(rawBody instanceof Map<?, ?> responseBody)) {
                throw new RealNameVerificationRejectedException("REAL_NAME_PROVIDER_UNAVAILABLE");
            }
            Object rawData = responseBody.get("Data");
            if (!(rawData instanceof Map<?, ?>)) rawData = responseBody.get("data");
            if (!(rawData instanceof Map<?, ?> data)) {
                throw new RealNameVerificationRejectedException("REAL_NAME_PROVIDER_UNAVAILABLE");
            }
            boolean pass = Boolean.TRUE.equals(data.get("Pass")) || Boolean.TRUE.equals(data.get("pass"));
            Object reference = data.get("VerificationToken") == null
                    ? data.get("verificationToken") : data.get("VerificationToken");
            return new VerificationResult(
                    pass,
                    reference == null ? null : reference.toString(),
                    pass ? null : "REAL_NAME_MISMATCH");
        } catch (RealNameVerificationRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RealNameVerificationRejectedException("REAL_NAME_PROVIDER_UNAVAILABLE");
        }
    }
}
