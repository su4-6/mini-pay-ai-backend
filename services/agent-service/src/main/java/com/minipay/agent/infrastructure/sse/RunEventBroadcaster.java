package com.minipay.agent.infrastructure.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.port.RunEventPublisher;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public final class RunEventBroadcaster implements RunEventPublisher {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final long emitterTimeoutMillis;

    public RunEventBroadcaster(
            ObjectMapper objectMapper,
            @Value("${minipay.agent.sse-idle-timeout:120s}") java.time.Duration emitterTimeout) {
        this.objectMapper = objectMapper;
        this.emitterTimeoutMillis = emitterTimeout.toMillis();
    }

    public Subscription subscribe(AgentRun run, long afterSequence) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        Subscription subscription = new Subscription(run, emitter, afterSequence);
        subscriptions.computeIfAbsent(run.id(), ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            remove(subscription);
            emitter.complete();
        });
        emitter.onError(ignored -> remove(subscription));
        return subscription;
    }

    @Override
    public void publish(AgentRunEvent event) {
        List<Subscription> runSubscriptions = subscriptions.get(event.runId());
        if (runSubscriptions == null) {
            return;
        }
        for (Subscription subscription : runSubscriptions) {
            subscription.acceptLive(event);
        }
    }

    @Scheduled(fixedDelayString = "${minipay.agent.sse-heartbeat:15s}")
    void heartbeat() {
        subscriptions.values().forEach(list -> list.forEach(Subscription::heartbeat));
    }

    private void remove(Subscription subscription) {
        CopyOnWriteArrayList<Subscription> values = subscriptions.get(subscription.run.id());
        if (values == null) {
            return;
        }
        values.remove(subscription);
        if (values.isEmpty()) {
            subscriptions.remove(subscription.run.id(), values);
        }
    }

    public final class Subscription {
        private final AgentRun run;
        private final SseEmitter emitter;
        private final AtomicLong lastSent;
        private final AtomicBoolean backlogComplete = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<AgentRunEvent> pendingLiveEvents = new ConcurrentLinkedQueue<>();

        private Subscription(AgentRun run, SseEmitter emitter, long afterSequence) {
            this.run = run;
            this.emitter = emitter;
            this.lastSent = new AtomicLong(afterSequence);
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public synchronized void sendBacklog(List<AgentRunEvent> backlog) {
            backlog.stream()
                    .sorted(Comparator.comparingLong(AgentRunEvent::sequenceNo))
                    .forEach(this::sendIfNew);
            backlogComplete.set(true);
            List<AgentRunEvent> pending = new ArrayList<>();
            AgentRunEvent event;
            while ((event = pendingLiveEvents.poll()) != null) {
                pending.add(event);
            }
            pending.stream()
                    .sorted(Comparator.comparingLong(AgentRunEvent::sequenceNo))
                    .forEach(this::sendIfNew);
        }

        private synchronized void acceptLive(AgentRunEvent event) {
            if (!backlogComplete.get()) {
                pendingLiveEvents.add(event);
                return;
            }
            sendIfNew(event);
        }

        private synchronized void sendIfNew(AgentRunEvent event) {
            if (event.sequenceNo() <= lastSent.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequenceNo()))
                        .name(event.eventType())
                        .data(envelope(event)));
                lastSent.set(event.sequenceNo());
                if ("stream.completed".equals(event.eventType())) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException exception) {
                remove(this);
                emitter.completeWithError(exception);
            }
        }

        private synchronized void heartbeat() {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(objectMapper.writeValueAsString(new Heartbeat(Instant.now()))));
            } catch (IOException | IllegalStateException exception) {
                remove(this);
                emitter.completeWithError(exception);
            }
        }

        private String envelope(AgentRunEvent event) {
            try {
                JsonNode payload = objectMapper.readTree(event.payload());
                return objectMapper.writeValueAsString(new EventEnvelope(
                        Long.toString(event.sequenceNo()), event.eventType(), event.payloadVersion(),
                        run.conversationId(), run.id(), event.occurredAt(), null, payload));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Stored agent event payload is invalid", exception);
            }
        }
    }

    private record EventEnvelope(
            String id,
            String type,
            int version,
            UUID conversationId,
            UUID runId,
            Instant occurredAt,
            String traceId,
            JsonNode payload) {
    }

    private record Heartbeat(Instant occurredAt) {
    }
}
