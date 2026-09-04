package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.BankCardService;
import com.minipay.payment.domain.model.BankCard;
import com.minipay.payment.domain.model.BankBalance;
import com.minipay.payment.domain.model.BankPaymentLimits;
import com.minipay.payment.domain.model.BankTransactionPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-cards")
public class BankCardController {
    private final BankCardService cards;

    public BankCardController(BankCardService cards) {
        this.cards = cards;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankCard bind(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BindBankCardRequest request) {
        UUID userId = ConsumerClaims.requireReadyUser(jwt, false);
        return cards.bind(
                userId,
                request.holderName(),
                request.cardNumber(),
                request.verificationCode());
    }

    @GetMapping
    public List<BankCard> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(ConsumerClaims.requireReadyUser(jwt, false));
    }

    @GetMapping("/{cardId}")
    public BankCard get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cardId) {
        return cards.get(ConsumerClaims.requireReadyUser(jwt, false), cardId);
    }

    @PostMapping("/{cardId}/balance-queries")
    public BankBalance balance(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cardId,
            @Valid @RequestBody BalanceQueryRequest request) {
        return cards.balance(
                ConsumerClaims.requireReadyUser(jwt, false),
                cardId,
                request.paymentAuthToken(),
                ConsumerClaims.requireDeviceId(jwt));
    }

    @GetMapping("/{cardId}/payment-limits")
    public BankPaymentLimits paymentLimits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cardId) {
        return cards.paymentLimits(
                ConsumerClaims.requireReadyUser(jwt, false), cardId);
    }

    @GetMapping("/{cardId}/transactions")
    public BankTransactionPage transactions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cardId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return cards.transactions(
                ConsumerClaims.requireReadyUser(jwt, false),
                cardId,
                from,
                to,
                page,
                size);
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cardId) {
        cards.disable(ConsumerClaims.requireReadyUser(jwt, false), cardId);
    }

    public record BindBankCardRequest(
            @NotBlank @Size(max = 64) String holderName,
            @NotBlank @Pattern(regexp = "^[0-9 ]{16,23}$") String cardNumber,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String verificationCode) {
    }

    public record BalanceQueryRequest(@NotBlank String paymentAuthToken) {
    }
}
