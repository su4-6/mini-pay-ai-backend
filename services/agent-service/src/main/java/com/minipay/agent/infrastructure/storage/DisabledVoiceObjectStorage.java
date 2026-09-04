package com.minipay.agent.infrastructure.storage;

import com.minipay.agent.application.port.VoiceObjectStorage;
import com.minipay.agent.application.service.VoiceMediaException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.agent.object-storage.provider", havingValue = "disabled", matchIfMissing = true)
public final class DisabledVoiceObjectStorage implements VoiceObjectStorage {
    private VoiceMediaException unavailable() { return new VoiceMediaException("OBJECT_STORAGE_UNAVAILABLE"); }
    @Override public SignedUpload signUpload(String key, String type, String sha, Duration ttl) { throw unavailable(); }
    @Override public SignedRead signRead(String key, Duration ttl) { throw unavailable(); }
    @Override public StoredObject head(String key) { throw unavailable(); }
    @Override public void delete(String key) { throw unavailable(); }
}
