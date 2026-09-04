package com.minipay.payment.application.service;

import com.minipay.payment.application.port.ObjectStoragePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 图片上传/读取（预签名直传 OSS）。
 *
 * <p>上传流程：前端提交文件元数据（contentType/size/sha256），本服务签发一个
 * 限时 PUT 预签名 URL；前端把文件字节直接 PUT 到 OSS，不经后端转发。读取时
 * 对 objectKey 批量签发限时 GET URL，前端直接拉取。</p>
 */
@Service
public class ImageStorageService {
    private static final Set<String> CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5MB
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    private static final Duration READ_TTL = Duration.ofMinutes(5);
    private static final int MAX_READ_KEYS = 20;
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");

    private final ObjectStoragePort storage;
    private final Clock clock;

    public ImageStorageService(ObjectStoragePort storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    public UploadGrant createUpload(
            String fileName, String contentType, long sizeBytes, String sha256) {
        String normalizedSha = sha256 == null ? "" : sha256.trim();
        if (fileName == null || fileName.isBlank() || !CONTENT_TYPES.contains(contentType)
                || sizeBytes <= 0 || sizeBytes > MAX_BYTES
                || !SHA256.matcher(normalizedSha).matches()) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "IMAGE_UPLOAD_INVALID",
                    "Image upload request is invalid: content type, size or digest mismatch");
        }
        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String objectKey = "merchants/images/" + UuidV7.generate(clock) + "." + extension;
        ObjectStoragePort.SignedUpload signed = storage.signUpload(
                objectKey, contentType, normalizedSha.toLowerCase(Locale.ROOT), UPLOAD_TTL);
        return new UploadGrant(
                signed.url().toString(), objectKey, signed.requiredHeaders(), signed.expiresAt());
    }

    public Map<String, String> readUrls(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty() || objectKeys.size() > MAX_READ_KEYS
                || objectKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "IMAGE_READ_INVALID",
                    "objectKeys must contain between 1 and " + MAX_READ_KEYS + " entries");
        }
        Map<String, String> urls = new LinkedHashMap<>();
        for (String objectKey : objectKeys) {
            urls.put(objectKey, storage.signRead(objectKey.trim(), READ_TTL).url().toString());
        }
        return urls;
    }

    public record UploadGrant(
            String uploadUrl,
            String objectKey,
            Map<String, String> requiredHeaders,
            Instant expiresAt) {
    }
}
