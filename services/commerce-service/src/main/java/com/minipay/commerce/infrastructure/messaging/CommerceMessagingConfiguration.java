package com.minipay.commerce.infrastructure.messaging;

import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommerceMessagingConfiguration {
    public static final String EVENTS_EXCHANGE = "minipay.events";
    public static final String PAYMENT_RESULTS_QUEUE = "commerce.payment-results.v1";
    public static final String PAYMENT_RESULTS_DEAD_QUEUE = "commerce.payment-results.dead.v1";
    public static final String IDENTITY_AUTHORIZATION_QUEUE = "commerce.identity-authorizations.v1";

    @Bean
    DirectExchange commerceEventsExchange() {
        return new DirectExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    Queue commercePaymentResultsQueue() {
        return new Queue(PAYMENT_RESULTS_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", EVENTS_EXCHANGE,
                "x-dead-letter-routing-key", "commerce.payment-results.dead"));
    }

    @Bean
    Queue commercePaymentResultsDeadQueue() {
        return new Queue(PAYMENT_RESULTS_DEAD_QUEUE, true, false, false);
    }

    @Bean
    Queue commerceIdentityAuthorizationQueue() {
        return new Queue(IDENTITY_AUTHORIZATION_QUEUE, true);
    }

    @Bean
    Declarables commerceIdentityAuthorizationBindings(
            Queue commerceIdentityAuthorizationQueue,
            DirectExchange commerceEventsExchange) {
        return new Declarables(List.of(
                binding(commerceIdentityAuthorizationQueue, commerceEventsExchange,
                        "identity.application-authorization.granted"),
                binding(commerceIdentityAuthorizationQueue, commerceEventsExchange,
                        "identity.application-authorization.revoked"),
                binding(commerceIdentityAuthorizationQueue, commerceEventsExchange,
                        "identity.user-disclosure-profile.changed")));
    }

    @Bean
    Binding commercePaymentResultsDeadBinding(
            Queue commercePaymentResultsDeadQueue,
            DirectExchange commerceEventsExchange) {
        return binding(commercePaymentResultsDeadQueue, commerceEventsExchange,
                "commerce.payment-results.dead");
    }

    @Bean
    Declarables commercePaymentResultBindings(
            Queue commercePaymentResultsQueue,
            DirectExchange commerceEventsExchange) {
        return new Declarables(List.of(
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.food-order.succeeded"),
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.food-order.failed"),
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.food-order.closed"),
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.refund.processing"),
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.refund.succeeded"),
                binding(commercePaymentResultsQueue, commerceEventsExchange,
                        "payment.refund.failed")));
    }

    private static Binding binding(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
