package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.application.port.ObjectStoragePort;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageStorageServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T01:00:00Z");
    private static final String SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock ObjectStoragePort storage;
    ImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new ImageStorageService(
                storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signsAnUploadForWhitelistedImageContentTypes() {
        when(storage.signUpload(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new ObjectStoragePort.SignedUpload(
                        URI.create("https://bucket.oss-cn-shanghai.aliyuncs.com/"
                                + invocation.getArgument(0)),
                        Map.of("Content-Type", invocation.getArgument(1)),
                        NOW.plusSeconds(600)));

        ImageStorageService.UploadGrant grant = service.createUpload(
                "shop.png", "image/png", 204800, SHA);

        assertThat(grant.objectKey()).startsWith("merchants/images/").endsWith(".png");
        assertThat(grant.uploadUrl()).contains(grant.objectKey());
        assertThat(grant.requiredHeaders()).containsEntry("Content-Type", "image/png");
        verify(storage).signUpload(eq(grant.objectKey()), eq("image/png"),
                eq(SHA), eq(Duration.ofMinutes(10)));
    }

    @Test
    void rejectsNonImageContentType() {
        assertThatThrownBy(() -> service.createUpload(
                "shop.pdf", "application/pdf", 1024, SHA))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IMAGE_UPLOAD_INVALID"));
    }

    @Test
    void rejectsOversizedFiles() {
        assertThatThrownBy(() -> service.createUpload(
                "shop.jpg", "image/jpeg", 5L * 1024 * 1024 + 1, SHA))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IMAGE_UPLOAD_INVALID"));
    }

    @Test
    void rejectsMalformedSha256() {
        assertThatThrownBy(() -> service.createUpload(
                "shop.jpg", "image/jpeg", 1024, "not-a-sha"))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IMAGE_UPLOAD_INVALID"));
    }

    @Test
    void signsReadUrlsForObjectKeys() {
        Map<String, ObjectStoragePort.SignedRead> reads = new LinkedHashMap<>();
        reads.put("merchants/images/a.jpg", new ObjectStoragePort.SignedRead(
                URI.create("https://bucket.oss-cn-shanghai.aliyuncs.com/merchants/images/a.jpg"),
                NOW.plusSeconds(300)));
        reads.put("merchants/images/b.jpg", new ObjectStoragePort.SignedRead(
                URI.create("https://bucket.oss-cn-shanghai.aliyuncs.com/merchants/images/b.jpg"),
                NOW.plusSeconds(300)));
        when(storage.signRead(anyString(), any()))
                .thenAnswer(invocation -> reads.get(invocation.getArgument(0)));

        Map<String, String> urls = service.readUrls(
                List.of("merchants/images/a.jpg", "merchants/images/b.jpg"));

        assertThat(urls).containsKeys("merchants/images/a.jpg", "merchants/images/b.jpg");
        verify(storage).signRead("merchants/images/a.jpg", Duration.ofMinutes(5));
        verify(storage).signRead("merchants/images/b.jpg", Duration.ofMinutes(5));
    }

    @Test
    void rejectsEmptyReadUrlList() {
        assertThatThrownBy(() -> service.readUrls(List.of()))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IMAGE_READ_INVALID"));
    }

    @Test
    void rejectsMoreThanTwentyReadKeys() {
        List<String> keys = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "merchants/images/" + index + ".jpg").toList();
        assertThatThrownBy(() -> service.readUrls(keys))
                .isInstanceOfSatisfying(OpsBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("IMAGE_READ_INVALID"));
    }
}
