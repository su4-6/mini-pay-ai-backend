package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.RefundService;
import com.minipay.payment.domain.model.Refund;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Refund create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        return refunds.create(
                idempotencyKey,
                request.paymentOrderId(),
                request.amountCent(),
                request.reason());
    }

    public record CreateRefundRequest(
            @NotNull UUID paymentOrderId,
            @Min(1) long amountCent,
            @Size(max = 256) String reason) {
    }
}
