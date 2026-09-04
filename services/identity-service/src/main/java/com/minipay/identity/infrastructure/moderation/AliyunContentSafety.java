package com.minipay.identity.infrastructure.moderation;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.port.ContentSafetyPort;
import com.minipay.identity.application.service.ProfileRejectedException;
import com.minipay.identity.infrastructure.aliyun.AliyunCredentialsFactory;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.identity.content-safety.provider", havingValue = "aliyun")
public final class AliyunContentSafety implements ContentSafetyPort {
    private final Client client;
    private final ObjectMapper objectMapper;
    private final String textService;
    private final String imageService;

    public AliyunContentSafety(
            ObjectMapper objectMapper,
            @Value("${minipay.identity.content-safety.endpoint}") String endpoint,
            @Value("${minipay.identity.content-safety.region}") String region,
            @Value("${minipay.identity.content-safety.ram-role-name}") String ramRoleName,
            @Value("${minipay.identity.content-safety.access-key-id}") String accessKeyId,
            @Value("${minipay.identity.content-safety.access-key-secret}") String accessKeySecret,
            @Value("${minipay.identity.content-safety.security-token:}") String securityToken,
            @Value("${minipay.identity.content-safety.text-service}") String textService,
            @Value("${minipay.identity.content-safety.image-service}") String imageService) throws Exception {
        Config config = new Config()
                .setRegionId(region)
                .setEndpoint(endpoint)
                .setCredential(AliyunCredentialsFactory.create(
                        ramRoleName, accessKeyId, accessKeySecret, securityToken));
        this.client = new Client(config);
        this.objectMapper = objectMapper;
        this.textService = textService;
        this.imageService = imageService;
    }

    @Override
    public boolean isNicknameAllowed(String nickname) {
        try {
            TextModerationResponse response = client.textModeration(new TextModerationRequest()
                    .setService(textService)
                    .setServiceParameters(json(Map.of("content", nickname))));
            if (response.getBody() == null || response.getBody().getCode() == null
                    || response.getBody().getCode() != 200 || response.getBody().getData() == null) {
                throw new ProfileRejectedException("CONTENT_REVIEW_UNAVAILABLE");
            }
            String labels = response.getBody().getData().getLabels();
            return labels == null || labels.isBlank() || "nonlabel".equalsIgnoreCase(labels);
        } catch (ProfileRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProfileRejectedException("CONTENT_REVIEW_UNAVAILABLE", exception);
        }
    }

    @Override
    public boolean isImageAllowed(URI signedImageUrl) {
        try {
            ImageModerationResponse response = client.imageModeration(new ImageModerationRequest()
                    .setService(imageService)
                    .setServiceParameters(json(Map.of("url", signedImageUrl.toString()))));
            if (response.getBody() == null || response.getBody().getCode() == null
                    || response.getBody().getCode() != 200 || response.getBody().getData() == null) {
                throw new ProfileRejectedException("CONTENT_REVIEW_UNAVAILABLE");
            }
            String risk = response.getBody().getData().getRiskLevel();
            if (risk != null && SetLikeRisks.isRejected(risk)) {
                return false;
            }
            return response.getBody().getData().getResult() == null
                    || response.getBody().getData().getResult().stream()
                    .noneMatch(result -> result.getRiskLevel() != null
                            && SetLikeRisks.isRejected(result.getRiskLevel()));
        } catch (ProfileRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProfileRejectedException("CONTENT_REVIEW_UNAVAILABLE", exception);
        }
    }

    private String json(Map<String, String> value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private static final class SetLikeRisks {
        private static boolean isRejected(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return normalized.equals("high") || normalized.equals("medium")
                    || normalized.equals("review") || normalized.equals("block");
        }
    }
}
