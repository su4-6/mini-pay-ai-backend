package co.yixiang.yshop.module.minipay.security;

import co.yixiang.yshop.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/** Registers the two MiniPay authentication boundaries with yshop's URL security chain. */
@Configuration(proxyBeanMethods = false)
public class MiniPaySecurityConfiguration {

    @Bean("miniPayAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer miniPayAuthorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {
            @Override
            public void customize(
                    AuthorizeHttpRequestsConfigurer<HttpSecurity>
                            .AuthorizationManagerRequestMatcherRegistry registry) {
                registry.requestMatchers(
                        "/internal/minipay/**",
                        "/app-api/minipay/auth/handoff").permitAll();
            }
        };
    }
}
