package com.minipay.payment.infrastructure.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.minipay.payment.application.port.ObjectStoragePort;
import com.minipay.payment.application.service.OpsBusinessException;
import com.minipay.payment.infrastructure.aliyun.AliyunCredentialsFactory;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.payment.object-storage.provider", havingValue = "aliyun")
public final class AliyunOssObjectStorage implements ObjectStoragePort {
    private final OSS oss;
    private final String bucket;

    public AliyunOssObjectStorage(
            @Value("${minipay.payment.object-storage.endpoint}") String endpoint,
            @Value("${minipay.payment.object-storage.region}") String region,
            @Value("${minipay.payment.object-storage.bucket}") String bucket,
            @Value("${minipay.payment.object-storage.ram-role-name:}") String ramRoleName,
            @Value("${minipay.payment.object-storage.access-key-id:}") String accessKeyId,
            @Value("${minipay.payment.object-storage.access-key-secret:}") String accessKeySecret,
            @Value("${minipay.payment.object-storage.security-token:}") String securityToken) {
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank()) {
            throw new IllegalStateException("Alibaba OSS endpoint, region and bucket are required");
        }
        com.aliyun.credentials.Client credentialClient = AliyunCredentialsFactory.create(
                ramRoleName, accessKeyId, accessKeySecret, securityToken);
        CredentialsProvider credentialsProvider = new CredentialsProvider() {
            @Override
            public void setCredentials(Credentials credentials) {
                // Credentials are owned and refreshed by the Alibaba Cloud provider chain.
            }

            @Override
            public Credentials getCredentials() {
                com.aliyun.credentials.models.CredentialModel credential =
                        credentialClient.getCredential();
                return new DefaultCredentials(
                        credential.getAccessKeyId(),
                        credential.getAccessKeySecret(),
                        credential.getSecurityToken());
            }
        };
        ClientBuilderConfiguration clientConfiguration = new ClientBuilderConfiguration();
        clientConfiguration.setSignatureVersion(SignVersion.V4);
        this.oss = OSSClientBuilder.create()
                .endpoint(endpoint.trim())
                .region(region.trim())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientConfiguration)
                .build();
        this.bucket = bucket.trim();
    }

    @Override
    public SignedUpload signUpload(
            String objectKey, String contentType, String sha256, Duration ttl) {
        try {
            Instant expiresAt = Instant.now().plus(ttl);
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.PUT);
            request.setExpiration(Date.from(expiresAt));
            request.setContentType(contentType);
            request.addUserMetadata("sha256", sha256);
            URI url = URI.create(oss.generatePresignedUrl(request).toString());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            headers.put("x-oss-meta-sha256", sha256);
            return new SignedUpload(url, Map.copyOf(headers), expiresAt);
        } catch (RuntimeException exception) {
            throw storageUnavailable(exception);
        }
    }

    @Override
    public SignedRead signRead(String objectKey, Duration ttl) {
        try {
            Instant expiresAt = Instant.now().plus(ttl);
            return new SignedRead(
                    URI.create(oss.generatePresignedUrl(bucket, objectKey, Date.from(expiresAt)).toString()),
                    expiresAt);
        } catch (RuntimeException exception) {
            throw storageUnavailable(exception);
        }
    }

    @Override
    public StoredObject head(String objectKey) {
        try {
            ObjectMetadata metadata = oss.headObject(bucket, objectKey);
            return new StoredObject(
                    metadata.getContentLength(),
                    metadata.getContentType(),
                    metadata.getUserMetadata().get("sha256"));
        } catch (RuntimeException exception) {
            throw storageUnavailable(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            oss.deleteObject(bucket, objectKey);
        } catch (RuntimeException exception) {
            throw storageUnavailable(exception);
        }
    }

    private static OpsBusinessException storageUnavailable(RuntimeException cause) {
        return new OpsBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE",
                "Object storage is unavailable");
    }

    @PreDestroy
    void shutdown() {
        oss.shutdown();
    }
}
