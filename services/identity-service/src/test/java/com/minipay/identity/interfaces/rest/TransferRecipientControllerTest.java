package com.minipay.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.TransferRecipientLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TransferRecipientControllerTest {
    @Test
    void resolvesFromTheAuthenticatedUserWithoutEchoingTheMobile() {
        TransferRecipientLookupService service = mock(TransferRecipientLookupService.class);
        TransferRecipientController controller = new TransferRecipientController(service);
        HttpServletRequest servlet = mock(HttpServletRequest.class);
        UUID requester = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(requester.toString())
                .claim("user_id", requester.toString())
                .claim("aud", java.util.List.of("consumer-api"))
                .build();
        when(servlet.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.resolveMobile(requester, "13800138000", "127.0.0.1"))
                .thenReturn(new TransferRecipientLookupService.RecipientView(
                        recipient, "小满", "138****8000", "张*", null, true));

        TransferRecipientLookupService.RecipientView response = controller.resolve(
                new JwtAuthenticationToken(jwt),
                new TransferRecipientController.ResolveTransferRecipientRequest("13800138000"),
                servlet);

        assertThat(response.toString()).doesNotContain("13800138000");
        verify(service).resolveMobile(requester, "13800138000", "127.0.0.1");
    }
}
