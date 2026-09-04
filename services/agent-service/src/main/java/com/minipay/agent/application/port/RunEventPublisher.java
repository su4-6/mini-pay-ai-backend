package com.minipay.agent.application.port;

import com.minipay.agent.domain.model.ai.AgentRunEvent;

public interface RunEventPublisher {
    void publish(AgentRunEvent event);
}
