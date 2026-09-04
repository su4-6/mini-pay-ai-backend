package com.minipay.payment.application.port;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface ObjectStoragePort {
    SignedUpload signUpload(String objectKey, String contentType, String sha256, Duration ttl);

    SignedRead signRead(String objectKey, Duration ttl);

    StoredObject head(String objectKey);

    void delete(String objectKey);

    record SignedUpload(URI url, Map<String, String> requiredHeaders, Instant expiresAt) {
    }

    record SignedRead(URI url, Instant expiresAt) {
    }

    record StoredObject(long size, String contentType, String sha256) {
    }
}
