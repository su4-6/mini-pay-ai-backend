package com.minipay.payment.infrastructure.storage;

import com.minipay.payment.application.port.ObjectStoragePort;
import com.minipay.payment.application.service.OpsBusinessException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.payment.object-storage.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public final class DisabledObjectStorage implements ObjectStoragePort {
    private static OpsBusinessException unavailable() {
        return new OpsBusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE", "Object storage is unavailable");
    }

    @Override public SignedUpload signUpload(String key, String type, String sha, Duration ttl) { throw unavailable(); }
    @Override public SignedRead signRead(String key, Duration ttl) { throw unavailable(); }
    @Override public StoredObject head(String key) { throw unavailable(); }
    @Override public void delete(String key) { throw unavailable(); }
}
