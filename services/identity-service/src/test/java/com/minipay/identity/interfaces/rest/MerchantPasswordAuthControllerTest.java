package com.minipay.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.CaptchaService;
import com.minipay.identity.application.service.ConsumerSmsChallengeService;
import com.minipay.identity.application.service.MerchantLoginPasswordService;
import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import com.minipay.identity.infrastructure.security.ConsumerAuthorizationCodeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MerchantPasswordAuthControllerTest {
    @Test
    void accountReturnsCurrentVerifiedMobileForTokenSubject() {
        ConsumerAccountRepository accounts = mock(ConsumerAccountRepository.class);
        UUID userId = UUID.randomUUID();
        when(accounts.requireVerifiedMobile(userId)).thenReturn("15036084035");
        MerchantPasswordAuthController controller = new MerchantPasswordAuthController(
                mock(PhoneNumberService.class), accounts, mock(MerchantLoginPasswordService.class),
                mock(ConsumerAuthorizationCodeService.class), mock(CaptchaService.class),
                mock(ConsumerSmsChallengeService.class));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(userId.toString()).build();

        assertThat(controller.account(jwt).phone()).isEqualTo("15036084035");
    }
}
