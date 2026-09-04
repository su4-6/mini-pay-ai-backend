package com.minipay.consumerbff.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveSecurityProblemHandler problems) {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new WebSessionServerCsrfTokenRepository())
                        .accessDeniedHandler(problems))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(
                                "/actuator/health/**", "/actuator/info", "/api/v1/csrf")
                        .permitAll()
                        .anyExchange().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .requestCache(cache -> cache.disable())
                .build();
    }
}
