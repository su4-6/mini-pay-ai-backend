package com.minipay.agent.infrastructure.storage;

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
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.minipay.agent.application.port.VoiceObjectStorage;
import com.minipay.agent.application.service.VoiceMediaException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.agent.object-storage.provider", havingValue = "aliyun")
public final class AliyunVoiceObjectStorage implements VoiceObjectStorage {
    private final OSS oss;
    private final String bucket;

    public AliyunVoiceObjectStorage(
            @Value("${minipay.agent.object-storage.endpoint}") String endpoint,
            @Value("${minipay.agent.object-storage.region}") String region,
            @Value("${minipay.agent.object-storage.bucket}") String bucket,
            @Value("${minipay.agent.object-storage.ram-role-name:}") String role,
            @Value("${minipay.agent.object-storage.access-key-id:}") String keyId,
            @Value("${minipay.agent.object-storage.access-key-secret:}") String keySecret,
            @Value("${minipay.agent.object-storage.security-token:}") String securityToken) {
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank()) {
            throw new IllegalStateException("Alibaba OSS endpoint, region and bucket are required");
        }
        Client client = new Client(credentials(role, keyId, keySecret, securityToken));
        CredentialsProvider provider = new CredentialsProvider() {
            @Override public void setCredentials(Credentials ignored) {}
            @Override public Credentials getCredentials() {
                var value = client.getCredential();
                return new DefaultCredentials(value.getAccessKeyId(), value.getAccessKeySecret(), value.getSecurityToken());
            }
        };
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        this.oss = OSSClientBuilder.create().endpoint(endpoint.trim()).region(region.trim())
                .credentialsProvider(provider).clientConfiguration(configuration).build();
        this.bucket = bucket.trim();
    }

    private static Config credentials(String role, String id, String secret, String token) {
        if (!role.isBlank()) return new Config().setType("ecs_ram_role").setRoleName(role.trim()).setEnableIMDSv2(true);
        if (id.isBlank() != secret.isBlank()) throw new IllegalStateException("Alibaba Cloud AccessKey configuration is incomplete");
        if (id.isBlank()) return new Config();
        Config result = new Config().setType(token.isBlank() ? "access_key" : "sts")
                .setAccessKeyId(id.trim()).setAccessKeySecret(secret.trim());
        return token.isBlank() ? result : result.setSecurityToken(token.trim());
    }

    @Override public SignedUpload signUpload(String key, String type, String sha, Duration ttl) {
        try {
            Instant expires = Instant.now().plus(ttl);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.PUT);
            request.setExpiration(Date.from(expires)); request.setContentType(type); request.addUserMetadata("sha256", sha);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", type); headers.put("x-oss-meta-sha256", sha);
            return new SignedUpload(URI.create(oss.generatePresignedUrl(request).toString()), Map.copyOf(headers), expires);
        } catch (RuntimeException e) { throw new VoiceMediaException("OBJECT_STORAGE_UNAVAILABLE", e); }
    }

    @Override public SignedRead signRead(String key, Duration ttl) {
        try {
            Instant expires = Instant.now().plus(ttl);
            return new SignedRead(URI.create(oss.generatePresignedUrl(bucket, key, Date.from(expires)).toString()), expires);
        } catch (RuntimeException e) { throw new VoiceMediaException("OBJECT_STORAGE_UNAVAILABLE", e); }
    }

    @Override public StoredObject head(String key) {
        try {
            ObjectMetadata metadata = oss.headObject(bucket, key);
            return new StoredObject(metadata.getContentLength(), metadata.getContentType(), metadata.getUserMetadata().get("sha256"));
        } catch (RuntimeException e) { throw new VoiceMediaException("VOICE_OBJECT_UNAVAILABLE", e); }
    }

    @Override public void delete(String key) {
        try { oss.deleteObject(bucket, key); }
        catch (RuntimeException e) { throw new VoiceMediaException("OBJECT_STORAGE_UNAVAILABLE", e); }
    }

    @PreDestroy void shutdown() { oss.shutdown(); }
}
