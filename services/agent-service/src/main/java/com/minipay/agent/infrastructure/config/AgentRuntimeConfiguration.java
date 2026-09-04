package com.minipay.agent.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class AgentRuntimeConfiguration {
    @Bean(name = "agentRunExecutor", destroyMethod = "close")
    ExecutorService agentRunExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
