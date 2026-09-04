package com.minipay.wallet.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BillTag(UUID tagId, String name, Instant createdAt) {
}
