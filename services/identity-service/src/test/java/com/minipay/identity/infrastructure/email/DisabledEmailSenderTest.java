package com.minipay.identity.infrastructure.email;

import com.minipay.identity.application.port.EmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledEmailSenderTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DisabledEmailSender.class);

    @Test
    void providesFallbackSenderWhenProviderIsMissing() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(EmailSender.class));
    }

    @Test
    void backsOffWhenAnotherProviderIsSelected() {
        contextRunner
                .withPropertyValues("minipay.identity.email.provider=smtp")
                .run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
    }
}
