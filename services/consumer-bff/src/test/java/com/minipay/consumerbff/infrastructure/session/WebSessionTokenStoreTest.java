package com.minipay.consumerbff.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.consumerbff.domain.security.Pkce;
import com.minipay.consumerbff.domain.session.ConsumerSession;
import com.minipay.consumerbff.domain.session.ConsumerTokens;
import com.minipay.consumerbff.domain.session.LoginChallenge;
import com.minipay.consumerbff.infrastructure.security.ConsumerSessionAuthenticationConverter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebSession;

class WebSessionTokenStoreTest {

    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/session"));
    private final WebSessionTokenStore store =
            new WebSessionTokenStore(exchange.getSession());

    @Test
    void storesTheSessionSummaryInsideTheServerSideSession() {
        store.storeTokens(new ConsumerTokens("access-1", "refresh-1")).block();
        store.storeConsumerSession(session()).block();

        WebSession webSession = exchange.getSession().block();
        assertThat(webSession).isNotNull();
        assertThat((Object) webSession.getAttribute(ConsumerSessionAttributes.ACCESS_TOKEN))
                .isEqualTo("access-1");
        assertThat((Object) webSession.getAttribute(ConsumerSessionAttributes.REFRESH_TOKEN))
                .isEqualTo("refresh-1");
        assertThat(store.loadConsumerSession().block()).isEqualTo(session());
    }

    @Test
    void rotateSessionIdAsksTheFrameworkToChangeTheSessionIdentifier() {
        // 会话固定防护的核心动作：登录成功后必须让框架换掉会话标识。
        // 这里在单元层证明"确实请求了轮换"，因为集成切片用的是 Boot 默认的内存 WebSession
        // （changeSessionId() 是空操作），在那里无法观测 id 变化；线上由 Spring Session Redis 承担。
        WebSession session = org.mockito.Mockito.mock(WebSession.class);
        new WebSessionTokenStore(reactor.core.publisher.Mono.just(session)).rotateSessionId().block();

        org.mockito.Mockito.verify(session).changeSessionId();
        org.mockito.Mockito.verify(session).setMaxIdleTime(java.time.Duration.ofMinutes(30));
    }

    @Test
    void authenticatedStateWrittenAfterRotationIsImmediatelyReadable() {
        // Mirrors the login order: rotate first, then persist the authenticated state. The point of
        // the rotation is that the pre-authentication identifier cannot survive into the
        // authenticated session; 轮换本身由
        // {@link #rotateSessionIdAsksTheFrameworkToChangeTheSessionIdentifier()} 在单元层证明。
        store.rotateSessionId().block();
        store.storeTokens(new ConsumerTokens("access-2", "refresh-2")).block();
        store.storeConsumerSession(session()).block();

        assertThat(store.loadConsumerSession().block()).isEqualTo(session());
        assertThat(store.loadTokens().block()).isEqualTo(new ConsumerTokens("access-2", "refresh-2"));
        assertThat(new ConsumerSessionAuthenticationConverter()
                .convert(exchange)
                .blockOptional()).isPresent();
    }

    @Test
    void storesThePkceVerifierWithoutExposingTheChallenge() {
        Pkce pkce = Pkce.generate();
        store.storeLoginChallenge(new LoginChallenge(pkce, "device-1", "challenge-1")).block();

        LoginChallenge loaded = store.loadLoginChallenge().block();

        assertThat(loaded).isNotNull();
        assertThat(loaded.pkce().verifier()).isEqualTo(pkce.verifier());
        assertThat(loaded.pkce().challenge()).isEqualTo(pkce.challenge());
        assertThat(loaded.deviceId()).isEqualTo("device-1");
        assertThat(loaded.challengeId()).isEqualTo("challenge-1");
        assertThat(store.loadDeviceId().block()).isEqualTo("device-1");
    }

    @Test
    void emptySessionReportsNoTokensAndNoConsumer() {
        assertThat(store.loadTokens().block()).isEqualTo(new ConsumerTokens(null, null));
        assertThat(store.loadConsumerSession().blockOptional()).isEmpty();
        assertThat(store.loadLoginChallenge().blockOptional()).isEmpty();
    }

    @Test
    void authenticatedSessionYieldsAConsumerPrincipal() {
        store.storeConsumerSession(session()).block();

        var authentication = new ConsumerSessionAuthenticationConverter().convert(exchange).block();

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(session().consumerId().toString());
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_CONSUMER");
    }

    @Test
    void clearingTheLoginChallengeKeepsTheDeviceBinding() {
        store.storeLoginChallenge(
                new LoginChallenge(Pkce.generate(), "device-1", "challenge-1")).block();

        store.clearLoginChallenge().block();

        assertThat(store.loadLoginChallenge().blockOptional()).isEmpty();
        assertThat(store.loadDeviceId().block()).isEqualTo("device-1");
    }

    @Test
    void invalidatingClearsEveryAttributeSoTheCookieCannotBeReused() {
        store.storeTokens(new ConsumerTokens("access-1", "refresh-1")).block();
        store.storeConsumerSession(session()).block();

        store.invalidate().block();

        assertThat(exchange.getSession().block().getAttributes()).isEmpty();
        assertThat(store.loadConsumerSession().blockOptional()).isEmpty();
    }

    private static ConsumerSession session() {
        return new ConsumerSession(
                UUID.fromString("0198f200-0000-7000-8000-000000000001"),
                "138****8000",
                "米灵用户",
                true,
                false,
                "VERIFIED",
                true);
    }
}
