package com.minipay.payment.infrastructure.client;

import com.minipay.payment.application.service.PaymentProblemException;
import java.util.Map;
import java.util.UUID;
import org.apache.seata.core.context.RootContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class WalletInternalClient {
    private final RestClient wallet;
    private final ServiceTokenProvider tokens;

    public WalletInternalClient(
            @Value("${minipay.clients.wallet.base-url}") String walletBaseUrl,
            ServiceTokenProvider tokens) {
        this.wallet = RestClient.builder().baseUrl(walletBaseUrl).build();
        this.tokens = tokens;
    }

    public void creditRecharge(
            UUID eventId,
            UUID rechargeId,
            String rechargeNo,
            UUID userId,
            long amountCent) {
        post("/internal/v1/wallet-postings/recharges", "event:" + eventId, Map.of(
                "eventId", eventId,
                "rechargeId", rechargeId,
                "rechargeNo", rechargeNo,
                "userId", userId,
                "amountCent", amountCent));
    }

    /** Idempotently provisions a personal wallet for a newly linked merchant owner. */
    public void openWallet(UUID eventId, UUID userId) {
        post("/internal/v1/wallet-postings/opening-grants", "event:" + eventId, Map.of(
                "eventId", eventId,
                "userId", userId));
    }

    public ResolvedAccount resolveAccount(UUID userId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = wallet.post()
                    .uri("/internal/v1/wallet-accounts/resolve")
                    .header("Authorization", "Bearer " + tokens.walletToken())
                    .body(Map.of("ownerId", userId))
                    .retrieve()
                    .body(Map.class);
            if (response == null
                    || response.get("accountId") == null
                    || !"ACTIVE".equals(response.get("status"))) {
                throw new PaymentProblemException("WALLET_NOT_ACTIVE", HttpStatus.CONFLICT);
            }
            return new ResolvedAccount(
                    userId,
                    UUID.fromString(response.get("accountId").toString()),
                    response.get("status").toString());
        } catch (RestClientResponseException exception) {
            throw new PaymentProblemException(
                    "WALLET_ACCOUNT_RESOLUTION_FAILED", HttpStatus.BAD_GATEWAY);
        }
    }

    public boolean tryDebit(
            UUID ownerId, String businessNo, String source, UUID accountId,
            UUID counterpartyOwnerId, long amountCent, String annualLimitMode) {
        return branch("/internal/v1/tcc/debits/try", Map.of(
                "businessNo", businessNo,
                "source", source,
                "ownerId", ownerId,
                "accountId", accountId,
                "counterpartyOwnerId", counterpartyOwnerId,
                "amountCent", amountCent,
                "annualLimitMode", annualLimitMode));
    }

    public boolean tryCredit(
            UUID ownerId, String businessNo, String source, UUID accountId,
            UUID counterpartyOwnerId, long amountCent) {
        return branch("/internal/v1/tcc/credits/try", Map.of(
                "businessNo", businessNo,
                "source", source,
                "ownerId", ownerId,
                "accountId", accountId,
                "counterpartyOwnerId", counterpartyOwnerId,
                "amountCent", amountCent));
    }

    public TransferOutcome transferOutcome(
            String businessNo, UUID payerAccountId, UUID receiverAccountId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = wallet.post()
                    .uri("/internal/v1/tcc/transfers/outcomes")
                    .header("Authorization", "Bearer " + tokens.walletToken())
                    .body(Map.of(
                            "businessNo", businessNo,
                            "payerAccountId", payerAccountId,
                            "receiverAccountId", receiverAccountId))
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new PaymentProblemException(
                        "WALLET_TRANSFER_OUTCOME_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
            }
            return new TransferOutcome(
                    response.get("businessNo").toString(),
                    response.get("debitStatus").toString(),
                    response.get("creditStatus").toString());
        } catch (RestClientException exception) {
            throw new PaymentProblemException(
                    "WALLET_TRANSFER_OUTCOME_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
        }
    }

    public void debitWithdrawal(
            UUID withdrawalId, String withdrawalNo, UUID userId, long amountCent) {
        post("/internal/v1/wallet-postings/withdrawals", "withdrawal:" + withdrawalId, Map.of(
                "withdrawalId", withdrawalId,
                "withdrawalNo", withdrawalNo,
                "userId", userId,
                "amountCent", amountCent));
    }

    public void completeWithdrawal(UUID withdrawalId, String withdrawalNo, UUID userId) {
        post("/internal/v1/wallet-postings/withdrawals/complete",
                "withdrawal-complete:" + withdrawalId,
                Map.of("withdrawalId", withdrawalId, "withdrawalNo", withdrawalNo,
                        "userId", userId));
    }

    public void reverseWithdrawal(
            UUID withdrawalId,
            String withdrawalNo,
            UUID userId,
            long amountCent,
            String failureCode) {
        post("/internal/v1/wallet-postings/withdrawals/reverse",
                "withdrawal-reverse:" + withdrawalId,
                Map.of("withdrawalId", withdrawalId, "withdrawalNo", withdrawalNo,
                        "userId", userId, "amountCent", amountCent,
                        "failureCode", failureCode));
    }

    public void debitPayment(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID userId,
            long amountCent,
            String subject,
            String annualLimitMode) {
        post("/internal/v1/wallet-postings/payments", "payment:" + paymentOrderId, Map.of(
                "paymentOrderId", paymentOrderId,
                "paymentOrderNo", paymentOrderNo,
                "userId", userId,
                "amountCent", amountCent,
                "subject", subject,
                "annualLimitMode", annualLimitMode));
    }

    public void creditRefund(
            UUID refundId, String refundNo, UUID userId, long amountCent,
            String originalPaymentNo) {
        post("/internal/v1/wallet-postings/refunds", "refund:" + refundId, Map.of(
                "refundId", refundId,
                "refundNo", refundNo,
                "userId", userId,
                "amountCent", amountCent,
                "originalPaymentNo", originalPaymentNo));
    }

    public void postMerchantPayment(
            UUID paymentOrderId,
            String paymentOrderNo,
            UUID payerUserId,
            UUID merchantOwnerUserId,
            long amountCent,
            String subject,
            String annualLimitMode,
            String fundingSource,
            String merchantName) {
        post("/internal/v1/wallet-postings/merchant-payments",
                "merchant-payment:" + paymentOrderId,
                Map.of(
                        "paymentOrderId", paymentOrderId,
                        "paymentOrderNo", paymentOrderNo,
                        "payerUserId", payerUserId,
                        "merchantOwnerUserId", merchantOwnerUserId,
                        "amountCent", amountCent,
                        "subject", subject,
                        "annualLimitMode", annualLimitMode,
                        "fundingSource", fundingSource,
                        "merchantName", merchantName));
    }

    public void postMerchantRefund(
            UUID refundId,
            String refundNo,
            UUID payerUserId,
            UUID merchantOwnerUserId,
            long amountCent,
            String originalPaymentNo,
            String merchantName) {
        post("/internal/v1/wallet-postings/merchant-refunds",
                "merchant-refund:" + refundId,
                Map.of(
                        "refundId", refundId,
                        "refundNo", refundNo,
                        "payerUserId", payerUserId,
                        "merchantOwnerUserId", merchantOwnerUserId,
                        "amountCent", amountCent,
                        "originalPaymentNo", originalPaymentNo,
                        "merchantName", merchantName));
    }

    private void post(String uri, String idempotencyKey, Object body) {
        try {
            wallet.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + tokens.walletToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> problem = exception.getResponseBodyAs(Map.class);
                if (problem != null
                        && "ANNUAL_OUTFLOW_LIMIT_EXCEEDED".equals(problem.get("code"))) {
                    throw new PaymentProblemException(
                            "ANNUAL_OUTFLOW_LIMIT_EXCEEDED", HttpStatus.CONFLICT);
                }
                if (problem != null
                        && "MERCHANT_REFUND_INSUFFICIENT_BALANCE".equals(problem.get("code"))) {
                    throw new PaymentProblemException(
                            "MERCHANT_REFUND_INSUFFICIENT_BALANCE", HttpStatus.CONFLICT);
                }
            } catch (PaymentProblemException problem) {
                throw problem;
            } catch (RuntimeException ignored) {
                // Use the generic posting error below.
            }
            HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
            throw new PaymentProblemException(
                    "WALLET_POSTING_REJECTED",
                    status == null ? HttpStatus.BAD_GATEWAY : status);
        }
    }

    private boolean branch(String uri, Object body) {
        String xid = RootContext.getXID();
        if (xid == null || xid.isBlank()) {
            throw new PaymentProblemException(
                    "MISSING_SEATA_XID", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = wallet.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + tokens.walletToken())
                    .header(RootContext.KEY_XID, xid)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response != null && Boolean.TRUE.equals(response.get("accepted"));
        } catch (RestClientResponseException exception) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> problem = exception.getResponseBodyAs(Map.class);
                if (problem != null
                        && "ANNUAL_OUTFLOW_LIMIT_EXCEEDED".equals(problem.get("code"))) {
                    throw new PaymentProblemException(
                            "ANNUAL_OUTFLOW_LIMIT_EXCEEDED", HttpStatus.CONFLICT);
                }
            } catch (PaymentProblemException problem) {
                throw problem;
            } catch (RuntimeException ignored) {
                // Fall through to the stable upstream-unavailable error.
            }
            throw new PaymentProblemException(
                    "WALLET_TCC_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
        } catch (RestClientException exception) {
            throw new PaymentProblemException(
                    "WALLET_TCC_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
        }
    }

    public record ResolvedAccount(UUID ownerId, UUID accountId, String status) {
    }

    public record TransferOutcome(
            String businessNo, String debitStatus, String creditStatus) {
    }
}
