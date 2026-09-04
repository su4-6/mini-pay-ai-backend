package com.minipay.agent.infrastructure.client;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public final class PaymentTransferVerificationClient {
    private final WebClient webClient;
    private final String paymentBaseUrl;
    private final Duration timeout;

    public PaymentTransferVerificationClient(
            WebClient.Builder builder,
            @Value("${minipay.agent.downstream.payment-base-url}") String paymentBaseUrl,
            @Value("${minipay.agent.downstream.timeout:5s}") Duration timeout) {
        this.webClient = builder.build();
        this.paymentBaseUrl = paymentBaseUrl;
        this.timeout = timeout;
    }

    public TransferReceipt requireSucceeded(String bearerToken, UUID transferId) {
        try {
            TransferReceipt receipt = webClient.get()
                    .uri(paymentBaseUrl + "/api/v1/transfer-orders/" + transferId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .bodyToMono(TransferReceipt.class)
                    .block(timeout);
            if (receipt == null || !"SUCCEEDED".equals(receipt.status())) {
                throw new IllegalArgumentException("TRANSFER_NOT_SUCCEEDED");
            }
            return receipt;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("TRANSFER_VERIFICATION_FAILED", error);
        }
    }

    public record TransferReceipt(UUID transferId, UUID intentId, UUID receiverUserId,
                                  long amountCent, String status, String failureCode,
                                  java.time.Instant updatedAt) {}
}
