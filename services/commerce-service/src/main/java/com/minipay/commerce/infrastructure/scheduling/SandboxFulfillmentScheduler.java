package com.minipay.commerce.infrastructure.scheduling;

import com.minipay.commerce.application.CommerceApplicationException;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxFulfillmentScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SandboxFulfillmentScheduler.class);
    private final CommerceOrderService orders;
    private final Clock clock;
    private final Duration acceptanceDelay;
    private final Duration preparationDelay;
    private final Duration deliveryStartDelay;
    private final Duration deliveredDelay;

    @Autowired
    public SandboxFulfillmentScheduler(
            CommerceOrderService orders,
            @Value("${minipay.commerce.sandbox.acceptance-delay:15s}") Duration acceptanceDelay,
            @Value("${minipay.commerce.sandbox.preparation-delay:30s}") Duration preparationDelay,
            @Value("${minipay.commerce.sandbox.delivery-start-delay:45s}") Duration deliveryStartDelay,
            @Value("${minipay.commerce.sandbox.delivered-delay:60s}") Duration deliveredDelay) {
        this(orders, Clock.systemUTC(), acceptanceDelay, preparationDelay, deliveryStartDelay, deliveredDelay);
    }

    SandboxFulfillmentScheduler(
            CommerceOrderService orders,
            Clock clock,
            Duration acceptanceDelay,
            Duration preparationDelay,
            Duration deliveryStartDelay,
            Duration deliveredDelay) {
        this.orders = orders;
        this.clock = clock;
        this.acceptanceDelay = acceptanceDelay;
        this.preparationDelay = preparationDelay;
        this.deliveryStartDelay = deliveryStartDelay;
        this.deliveredDelay = deliveredDelay;
    }

    @Scheduled(fixedDelayString = "${minipay.commerce.sandbox.progress-delay:5s}")
    public void progress() {
        progress(FoodOrderStatus.PENDING_PAYMENT, Duration.ZERO, null);
        progress(FoodOrderStatus.PAID, acceptanceDelay, FoodOrderStatus.MERCHANT_ACCEPTED);
        progress(FoodOrderStatus.MERCHANT_ACCEPTED, preparationDelay, FoodOrderStatus.PREPARING);
        progress(FoodOrderStatus.PREPARING, deliveryStartDelay, FoodOrderStatus.DELIVERING);
        progress(FoodOrderStatus.DELIVERING, deliveredDelay, FoodOrderStatus.DELIVERED);
    }

    private void progress(FoodOrderStatus source, Duration age, FoodOrderStatus target) {
        Instant cutoff = clock.instant().minus(age);
        orders.progressCandidates(source, cutoff, 50).forEach(order -> {
            try {
                if (target == null) orders.expireUnpaid(order.id());
                else orders.advanceSandbox(order.id(), target);
            } catch (CommerceApplicationException exception) {
                LOG.debug("Sandbox order {} was concurrently updated: {}", order.id(), exception.code());
            }
        });
    }
}
