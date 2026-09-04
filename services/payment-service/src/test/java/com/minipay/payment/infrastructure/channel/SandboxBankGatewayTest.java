package com.minipay.payment.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.payment.application.port.BankGateway.TokenizedCard;
import com.minipay.payment.application.service.PaymentProblemException;
import com.minipay.payment.infrastructure.persistence.SandboxBankRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SandboxBankGatewayTest {
    private final SandboxBankRepository repository = mock(SandboxBankRepository.class);
    private final SandboxBankGateway gateway = new SandboxBankGateway(
            "test-bank-tokenization-key-which-is-long-enough",
            10_000_000L,
            1_000_000L,
            5_000_000L,
            repository);

    @Test
    void tokenizesWithoutReturningOrPersistingThePan() {
        TokenizedCard card = gateway.bind(
                UUID.fromString("0197f000-0000-7000-8000-000000000001"),
                "测试用户",
                "4111 1111 1111 1111",
                "123456");

        assertThat(card.provider()).isEqualTo("SANDBOX_BANK");
        assertThat(card.cardType()).isEqualTo("DEBIT");
        assertThat(card.maskedCardNo()).isEqualTo("**** **** **** 1111");
        assertThat(card.lastFour()).isEqualTo("1111");
        assertThat(card.providerToken())
                .hasSize(64)
                .doesNotContain("4111111111111111");
        verify(repository).initialize(card.providerToken(), 10_000_000L, 1_000_000L, 5_000_000L);
    }

    @Test
    void rejectsAnInvalidVerificationCode() {
        assertThatThrownBy(() -> gateway.bind(
                UUID.randomUUID(),
                "测试用户",
                "4111111111111111",
                "000000"))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("BANK_VERIFICATION_FAILED");
    }

    @Test
    void acceptsSandboxFormatOnlyNineteenDigitCardNumbers() {
        TokenizedCard card = gateway.bind(
                UUID.fromString("0197f000-0000-7000-8000-000000000001"),
                "测试用户",
                "6222021234567890125",
                "123456");

        assertThat(card.lastFour()).isEqualTo("0125");
    }

    @Test
    void rejectsNonAsciiOrOutOfRangeCardNumbers() {
        assertThatThrownBy(() -> gateway.bind(
                UUID.randomUUID(), "测试用户", "622202123456789", "123456"))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("INVALID_BANK_CARD");
        assertThatThrownBy(() -> gateway.bind(
                UUID.randomUUID(), "测试用户", "6222021234567890125A", "123456"))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("INVALID_BANK_CARD");
    }
}
