package com.minipay.payment.application.port;

import java.time.Instant;
import java.util.UUID;

public interface OperationAuditStore {
    void append(
            UUID auditId,
            String actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String beforeDigest,
            String afterDigest,
            String requestId,
            Instant occurredAt);
}
