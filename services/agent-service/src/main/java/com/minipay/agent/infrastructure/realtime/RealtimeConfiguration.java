package com.minipay.agent.infrastructure.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RealtimeConfiguration implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    public RealtimeConfiguration(RealtimeWebSocketHandler handler) { this.handler = handler; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) { registry.addHandler(handler, "/api/v1/agent/realtime").setAllowedOriginPatterns("*"); }
    @Bean RedisMessageListenerContainer realtimeRedisListener(RedisConnectionFactory factory, RealtimeSessionRegistry sessions) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer(); container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> sessions.deliver(message.toString()), new ChannelTopic(RealtimeSessionRegistry.CHANNEL)); return container;
    }
}
