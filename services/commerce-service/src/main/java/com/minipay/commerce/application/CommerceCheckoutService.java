package com.minipay.commerce.application;

import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.CheckoutQuote;
import com.minipay.commerce.domain.model.FoodOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceCheckoutService {
    private static final DateTimeFormatter ORDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final CommerceRepository repository;
    private final Clock clock;
    private final Duration quoteTtl;
    private final Duration orderPaymentTtl;

    @Autowired
    public CommerceCheckoutService(
            CommerceRepository repository,
            @Value("${minipay.commerce.quote-ttl:10m}") Duration quoteTtl,
            @Value("${minipay.commerce.order-payment-ttl:15m}") Duration orderPaymentTtl) {
        this(repository, Clock.systemUTC(), quoteTtl, orderPaymentTtl);
    }

    CommerceCheckoutService(
            CommerceRepository repository,
            Clock clock,
            Duration quoteTtl,
            Duration orderPaymentTtl) {
        this.repository = repository;
        this.clock = clock;
        this.quoteTtl = quoteTtl;
        this.orderPaymentTtl = orderPaymentTtl;
    }

    @Transactional
    public CheckoutQuote prepareQuote(
            UUID userId,
            UUID merchantId,
            UUID addressId,
            long expectedCartVersion,
            String idempotencyKey) {
        byte[] hash = requestHash("QUOTE", userId.toString(), merchantId.toString(), addressId.toString(),
                Long.toString(expectedCartVersion), idempotencyKey);
        CheckoutQuote existing = repository.findQuoteByRequestHash(userId, hash).orElse(null);
        if (existing != null) return existing;
        Instant now = clock.instant();
        return repository.createQuote(
                UuidV7.generate(), userId, merchantId, addressId, expectedCartVersion,
                hash, now, now.plus(quoteTtl));
    }

    @Transactional
    public FoodOrder createOrder(
            UUID userId,
            UUID quoteId,
            String idempotencyKey) {
        byte[] hash = requestHash("ORDER", userId.toString(), quoteId.toString(), idempotencyKey);
        FoodOrder existing = repository.findOrderByRequestHash(userId, hash).orElse(null);
        if (existing != null) return existing;
        Instant now = clock.instant();
        UUID orderId = UuidV7.generate();
        String orderNo = "FO" + ORDER_TIME.format(now)
                + orderId.toString().replace("-", "").substring(0, 10).toUpperCase();
        return repository.createOrder(
                orderId, orderNo, userId, quoteId, hash, now, now.plus(orderPaymentTtl));
    }

    private static byte[] requestHash(String... values) {
        String key = values[values.length - 1];
        if (key == null || key.length() < 8 || key.length() > 128) {
            throw new CommerceApplicationException(
                    "COMMERCE_IDEMPOTENCY_KEY_INVALID", "幂等键长度必须为 8 到 128 个字符");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
