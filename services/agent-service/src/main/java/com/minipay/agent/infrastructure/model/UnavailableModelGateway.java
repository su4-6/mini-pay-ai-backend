package com.minipay.agent.infrastructure.model;

import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.application.service.ModelGatewayException;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "minipay.agent.model",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class UnavailableModelGateway implements ModelGateway {
    @Override
    public void streamText(ModelRequest request, Consumer<String> deltaConsumer) {
        throw new ModelGatewayException(
                "AGENT_MODEL_DISABLED", "模型服务未配置，请使用余额、转账或点餐快捷入口");
    }
}
