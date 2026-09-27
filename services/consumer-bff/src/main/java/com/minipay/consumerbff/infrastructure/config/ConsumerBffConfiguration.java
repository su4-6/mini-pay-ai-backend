package com.minipay.consumerbff.infrastructure.config;

import com.minipay.consumerbff.application.port.ConsumerApiGateway;
import com.minipay.consumerbff.application.port.ConsumerProfileGateway;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway;
import com.minipay.consumerbff.application.port.IdentityConsumerGateway;
import com.minipay.consumerbff.application.service.ConsumerSessionService;
import com.minipay.consumerbff.infrastructure.client.HttpConsumerApiGateway;
import com.minipay.consumerbff.infrastructure.client.HttpConsumerProfileGateway;
import com.minipay.consumerbff.infrastructure.client.HttpIdentityAuthorizationGateway;
import com.minipay.consumerbff.infrastructure.client.HttpIdentityConsumerGateway;
import com.minipay.consumerbff.infrastructure.client.UpstreamWebClients;
import com.minipay.consumerbff.infrastructure.session.WebSessionTokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

/**
 * Wires the consumer H5 BFF. Every upstream address comes from the environment with a localhost
 * default; no production credential or host name is baked into the build.
 *
 * <p>The session store is not a bean: WebFlux has no request scope, so the store is constructed per
 * request by the controllers from the {@code WebSession} they receive, and passed down explicitly.
 */
@Configuration
public class ConsumerBffConfiguration {

    /**
     * Binds the session id resolver to the cookie actually configured for this service.
     *
     * <p>Spring Session's resolver defaults to a cookie literally named {@code SESSION}, and Spring
     * Boot's property-driven auto-configuration only backs off when this bean is absent. Without
     * this, the BFF would write {@code __Host-minipay-consumer} but never read it back, silently
     * starting a brand new session on every request — which in production means a permanent login
     * loop and CSRF failures.
     */
    @Bean
    WebSessionIdResolver webSessionIdResolver(
            @Value("${server.reactive.session.cookie.name:__Host-minipay-consumer}") String name) {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName(name);
        return resolver;
    }

    @Bean
    WebClient consumerIdentityWebClient(@Value("${minipay.consumer-bff.identity-url}") String url) {
        return UpstreamWebClients.json(url);
    }

    @Bean
    WebClient consumerWalletWebClient(@Value("${minipay.consumer-bff.wallet-url}") String url) {
        return UpstreamWebClients.json(url);
    }

    @Bean
    WebClient consumerPaymentWebClient(@Value("${minipay.consumer-bff.payment-url}") String url) {
        return UpstreamWebClients.json(url);
    }

    @Bean
    WebClient consumerMilingWebClient(@Value("${minipay.consumer-bff.miling-url}") String url) {
        return UpstreamWebClients.json(url);
    }

    @Bean
    WebClient consumerMilingStreamingWebClient(
            @Value("${minipay.consumer-bff.miling-url}") String url) {
        return UpstreamWebClients.streaming(url);
    }

    @Bean
    IdentityAuthorizationGateway identityAuthorizationGateway(
            WebClient consumerIdentityWebClient,
            @Value("${minipay.consumer-bff.oauth-client-id}") String clientId,
            @Value("${minipay.consumer-bff.oauth-redirect-uri}") String redirectUri) {
        return new HttpIdentityAuthorizationGateway(
                consumerIdentityWebClient, clientId, redirectUri);
    }

    @Bean
    IdentityConsumerGateway identityConsumerGateway(WebClient consumerIdentityWebClient) {
        return new HttpIdentityConsumerGateway(consumerIdentityWebClient);
    }

    @Bean
    ConsumerProfileGateway consumerProfileGateway(WebClient consumerIdentityWebClient) {
        return new HttpConsumerProfileGateway(consumerIdentityWebClient);
    }

    @Bean
    ConsumerApiGateway consumerApiGateway(
            WebClient consumerIdentityWebClient,
            WebClient consumerWalletWebClient,
            WebClient consumerPaymentWebClient,
            WebClient consumerMilingWebClient,
            WebClient consumerMilingStreamingWebClient,
            IdentityAuthorizationGateway identityAuthorizationGateway) {
        return new HttpConsumerApiGateway(
                consumerIdentityWebClient,
                consumerWalletWebClient,
                consumerPaymentWebClient,
                consumerMilingWebClient,
                consumerMilingStreamingWebClient,
                identityAuthorizationGateway);
    }

    @Bean
    ConsumerSessionService consumerSessionService(
            IdentityAuthorizationGateway identityAuthorizationGateway,
            IdentityConsumerGateway identityConsumerGateway,
            ConsumerProfileGateway consumerProfileGateway,
            ConsumerApiGateway consumerApiGateway,
            @Value("${minipay.consumer-bff.oauth-client-id}") String clientId,
            @Value("${minipay.consumer-bff.oauth-redirect-uri}") String redirectUri) {
        return new ConsumerSessionService(
                identityAuthorizationGateway,
                identityConsumerGateway,
                consumerProfileGateway,
                consumerApiGateway,
                clientId,
                redirectUri);
    }

    /** Session store for one request, built from the WebSession the controller received. */
    public static WebSessionTokenStore sessionStore(
            org.springframework.web.server.WebSession session) {
        return new WebSessionTokenStore(reactor.core.publisher.Mono.just(session));
    }
}
