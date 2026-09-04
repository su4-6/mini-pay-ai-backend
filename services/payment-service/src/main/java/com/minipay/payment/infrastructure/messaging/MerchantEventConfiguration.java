package com.minipay.payment.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MerchantEventConfiguration {
    static final String METRIC_QUEUE = "minipay.merchant.metric.v1";
    static final String NOTIFICATION_QUEUE = "minipay.merchant.notification.v1";

    @Bean Queue merchantMetricQueue() { return new Queue(METRIC_QUEUE, true); }
    @Bean Queue merchantNotificationQueue() { return new Queue(NOTIFICATION_QUEUE, true); }
    @Bean Binding merchantMetricPaymentBinding(Queue merchantMetricQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(merchantMetricQueue).to(paymentEventsExchange).with("payment.order.succeeded");
    }
    @Bean Binding merchantMetricRefundBinding(Queue merchantMetricQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(merchantMetricQueue).to(paymentEventsExchange).with("payment.refund.succeeded");
    }
    @Bean Binding merchantNotificationPaymentBinding(Queue merchantNotificationQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(merchantNotificationQueue).to(paymentEventsExchange).with("payment.order.succeeded");
    }
    @Bean Binding merchantNotificationRefundBinding(Queue merchantNotificationQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(merchantNotificationQueue).to(paymentEventsExchange).with("payment.refund.succeeded");
    }
    @Bean Binding merchantNotificationFailedBinding(Queue merchantNotificationQueue, DirectExchange paymentEventsExchange) {
        return BindingBuilder.bind(merchantNotificationQueue).to(paymentEventsExchange).with("payment.order.failed");
    }
}
