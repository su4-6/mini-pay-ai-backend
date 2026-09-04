package com.minipay.identity.infrastructure.storage;

import com.minipay.identity.application.port.ObjectStoragePort;
import com.minipay.identity.application.service.ProfileRejectedException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "minipay.identity.object-storage.provider",
        havingValue = "disabled",
        matchIfMissing = true)
public final class DisabledObjectStorage implements ObjectStoragePort {
    private ProfileRejectedException unavailable() {
        return new ProfileRejectedException("OBJECT_STORAGE_UNAVAILABLE");
    }

    @Override public SignedUpload signUpload(String key, String type, String sha, Duration ttl) { throw unavailable(); }
    @Override public SignedRead signRead(String key, Duration ttl) { throw unavailable(); }
    @Override public StoredObject head(String key) { throw unavailable(); }
    @Override public void delete(String key) { throw unavailable(); }
}
