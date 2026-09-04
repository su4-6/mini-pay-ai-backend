package com.minipay.wallet.infrastructure.realtime;

import com.minipay.wallet.application.service.CollectionReceiptReady;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Ephemeral, authenticated foreground notifications; REST bill queries repair missed events. */
@Component
public class CollectionReceiptHub {
    private final Map<UUID, Map<String, SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID ownerId) {
        SseEmitter emitter = new SseEmitter(0L);
        String subscriptionId = UUID.randomUUID().toString();
        subscribers.computeIfAbsent(ownerId, ignored -> new ConcurrentHashMap<>())
                .put(subscriptionId, emitter);
        emitter.onCompletion(() -> remove(ownerId, subscriptionId));
        emitter.onTimeout(() -> remove(ownerId, subscriptionId));
        emitter.onError(ignored -> remove(ownerId, subscriptionId));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(CollectionReceiptReady receipt) {
        Map<String, SseEmitter> userSubscribers = subscribers.get(receipt.ownerId());
        if (userSubscribers == null) return;
        userSubscribers.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(receipt.eventId().toString())
                        .name("collection-receipt")
                        .data(receipt, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                remove(receipt.ownerId(), id);
            }
        });
    }

    private void remove(UUID ownerId, String subscriptionId) {
        Map<String, SseEmitter> userSubscribers = subscribers.get(ownerId);
        if (userSubscribers == null) return;
        userSubscribers.remove(subscriptionId);
        if (userSubscribers.isEmpty()) subscribers.remove(ownerId, userSubscribers);
    }
}
