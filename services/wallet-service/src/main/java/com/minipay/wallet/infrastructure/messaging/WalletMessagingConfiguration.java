package com.minipay.wallet.infrastructure.messaging;

import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletMessagingConfiguration {
    public static final String EXCHANGE = "minipay.events";

    @Bean
    DirectExchange minipayEventsExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue walletEventQueue() {
        return new Queue("minipay.wallet.p0-events", true);
    }

    @Bean
    Declarables walletEventBindings(
            DirectExchange minipayEventsExchange,
            Queue walletEventQueue) {
        Binding opened = BindingBuilder.bind(walletEventQueue)
                .to(minipayEventsExchange).with("identity.user.opened");
        Binding recharge = BindingBuilder.bind(walletEventQueue)
                .to(minipayEventsExchange).with("payment.recharge.succeeded");
        Binding transferProcessing = BindingBuilder.bind(walletEventQueue)
                .to(minipayEventsExchange).with("payment.transfer.processing");
        Binding transferSucceeded = BindingBuilder.bind(walletEventQueue)
                .to(minipayEventsExchange).with("payment.transfer.succeeded");
        Binding transferFailed = BindingBuilder.bind(walletEventQueue)
                .to(minipayEventsExchange).with("payment.transfer.failed");
        return new Declarables(List.of(
                opened,
                recharge,
                transferProcessing,
                transferSucceeded,
                transferFailed));
    }
}
