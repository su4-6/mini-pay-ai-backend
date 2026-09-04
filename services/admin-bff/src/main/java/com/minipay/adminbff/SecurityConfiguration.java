package com.minipay.adminbff;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;

@Configuration public class SecurityConfiguration {
 @Bean ReactiveOAuth2AuthorizedClientManager clients(ReactiveClientRegistrationRepository registrations,ServerOAuth2AuthorizedClientRepository repository){var provider=ReactiveOAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build();var manager=new DefaultReactiveOAuth2AuthorizedClientManager(registrations,repository);manager.setAuthorizedClientProvider(provider);return manager;}
 @Bean SecurityWebFilterChain chain(ServerHttpSecurity http,ReactiveClientRegistrationRepository clients,@Value("${minipay.admin-web-url}")String web,IdentityLogoutSuccessHandler logoutSuccess){
  var authorizationRequestResolver=new DefaultServerOAuth2AuthorizationRequestResolver(clients);
  authorizationRequestResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
  return http.csrf(c->c.csrfTokenRepository(new WebSessionServerCsrfTokenRepository()))
   .authorizeExchange(e->e.pathMatchers("/actuator/health/**","/api/v1/session","/api/v1/csrf","/oauth2/**","/login/**","/switch-login").permitAll()
    .pathMatchers("/api/v1/admin/**").access((a,c)->a.map(value->{if(!(value instanceof OAuth2AuthenticationToken t)||!(t.getPrincipal() instanceof OidcUser u))return new AuthorizationDecision(false);List<String> roles=u.getClaimAsStringList("roles");return new AuthorizationDecision(roles!=null&&roles.stream().anyMatch(r->r.equals("system_super_admin")||r.equals("system_account_admin")||r.equals("system_auditor")));}).defaultIfEmpty(new AuthorizationDecision(false)))
    .anyExchange().denyAll())
   .oauth2Login(o->o.loginPage(web + "login").authorizationRequestResolver(authorizationRequestResolver).authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler(web)).authenticationFailureHandler(new RedirectServerAuthenticationFailureHandler(web + "login?error=1")))
   .logout(l->l.logoutUrl("/logout").logoutSuccessHandler(logoutSuccess)).build();
 }
}
