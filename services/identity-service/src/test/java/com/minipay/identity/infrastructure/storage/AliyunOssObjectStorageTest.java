package com.minipay.identity.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AliyunOssObjectStorageTest {
    @Test
    void createsV4UploadGrantWithIntegrityMetadata() {
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(
                "https://oss-cn-hangzhou.aliyuncs.com",
                "cn-hangzhou",
                "minipay-private",
                "",
                "test-access-key-id",
                "test-access-key-secret",
                "");
        try {
            var grant = storage.signUpload(
                    "avatars/user/avatar.jpg",
                    "image/jpeg",
                    "a".repeat(64),
                    Duration.ofMinutes(10));

            assertThat(grant.url().toString())
                    .startsWith("https://minipay-private.oss-cn-hangzhou.aliyuncs.com/")
                    .contains("x-oss-signature-version=OSS4-HMAC-SHA256");
            assertThat(grant.requiredHeaders())
                    .containsEntry("Content-Type", "image/jpeg")
                    .containsEntry("x-oss-meta-sha256", "a".repeat(64));
        } finally {
            storage.shutdown();
        }
    }
}
