package com.minipay.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DomainEventWriter {
    private final PaymentRepository repository;
    private final ObjectMapper objectMapper;

    public DomainEventWriter(
            PaymentRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public UUID write(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, Object> payload) {
        UUID eventId = UuidV7.generate();
        try {
            repository.insertOutbox(
                    eventId,
                    eventType,
                    aggregateType,
                    aggregateId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(payload)));
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize domain event", exception);
        }
    }

    @Transactional
    public UUID writeAfter(
            Runnable stateChange,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, Object> payload) {
        stateChange.run();
        return write(eventType, aggregateType, aggregateId, payload);
    }

    @Transactional
    public UUID writeAfterWithAdditional(
            Runnable stateChange,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, Object> payload,
            String additionalEventType,
            String additionalAggregateType,
            UUID additionalAggregateId,
            Map<String, Object> additionalPayload) {
        stateChange.run();
        UUID eventId = write(eventType, aggregateType, aggregateId, payload);
        write(additionalEventType, additionalAggregateType,
                additionalAggregateId, additionalPayload);
        return eventId;
    }
}
