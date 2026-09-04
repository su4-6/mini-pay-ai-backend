package com.minipay.identity.infrastructure.messaging;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfiguration {
    @Bean
    DirectExchange minipayEventsExchange() {
        return new DirectExchange(OutboxPublisher.EXCHANGE, true, false);
    }
}
