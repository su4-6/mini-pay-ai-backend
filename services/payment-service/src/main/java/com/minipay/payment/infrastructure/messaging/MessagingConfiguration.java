package com.minipay.payment.infrastructure.messaging;

import java.util.Map;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfiguration {
    public static final String FOOD_ORDER_QUEUE = "payment.food-order-created.v1";
    public static final String FOOD_ORDER_DEAD_QUEUE = "payment.food-order-created.dead.v1";
    @Bean
    DirectExchange paymentEventsExchange() {
        return new DirectExchange(OutboxPublisher.EXCHANGE, true, false);
    }

    @Bean
    Queue paymentFoodOrderQueue() {
        return new Queue(FOOD_ORDER_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", OutboxPublisher.EXCHANGE,
                "x-dead-letter-routing-key", "payment.food-order.dead"));
    }

    @Bean
    Queue paymentFoodOrderDeadQueue() {
        return new Queue(FOOD_ORDER_DEAD_QUEUE, true, false, false);
    }

    @Bean
    Binding paymentFoodOrderDeadBinding(
            Queue paymentFoodOrderDeadQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentFoodOrderDeadQueue).to(paymentEventsExchange)
                .with("payment.food-order.dead");
    }

    @Bean
    Binding paymentFoodOrderBinding(Queue paymentFoodOrderQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentFoodOrderQueue).to(paymentEventsExchange)
                .with("commerce.food-order.created");
    }

    @Bean
    Binding paymentFoodRefundBinding(Queue paymentFoodOrderQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentFoodOrderQueue).to(paymentEventsExchange)
                .with("commerce.refund.requested");
    }
}
