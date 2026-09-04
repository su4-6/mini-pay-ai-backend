package com.minipay.commerce.infrastructure.yshop;

import static com.minipay.commerce.infrastructure.yshop.YshopModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class YshopGateway {
    private final RestClient client;
    private final ObjectMapper json;
    private final byte[] secret;

    public YshopGateway(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value("${minipay.commerce.yshop.base-url:http://localhost:48080}") String baseUrl,
            @Value("${minipay.commerce.yshop.hmac-secret:}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.json = json;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public IdentityView resolveIdentity(IdentityRequest request) {
        return post("/internal/minipay/v1/identities/resolve", request, IdentityView.class, null);
    }

    public IdentityView identity(String subject) {
        String path = "/internal/minipay/v1/identities/" + subject;
        return get(path, uri -> uri, new ParameterizedTypeReference<>() { });
    }

    public void revokeSessions(String subject) {
        post("/internal/minipay/v1/identities/" + subject + "/sessions/revoke",
                java.util.Map.of(), Object.class, null);
    }

    public void detachIdentity(String subject) {
        delete("/internal/minipay/v1/identities/" + subject);
    }

    public List<Store> stores(double longitude, double latitude, String fulfillment, double radiusKm) {
        String path = "/internal/minipay/v1/stores";
        return get(path, uri -> uri.queryParam("longitude", longitude)
                        .queryParam("latitude", latitude)
                        .queryParam("fulfillmentType", fulfillment)
                        .queryParam("radiusKm", radiusKm),
                new ParameterizedTypeReference<>() { });
    }

    public List<Product> menu(long shopId) {
        return get("/internal/minipay/v1/stores/" + shopId + "/menu", uri -> uri,
                new ParameterizedTypeReference<>() { });
    }

    public List<Address> addresses(String subject) {
        return get("/internal/minipay/v1/addresses", uri -> uri.queryParam("subject", subject),
                new ParameterizedTypeReference<>() { });
    }

    public Quote quote(QuoteRequest request) {
        return post("/internal/minipay/v1/checkout-quotes", request, Quote.class, null);
    }

    public Order createOrder(CreateOrderRequest request, String idempotencyKey) {
        return post("/internal/minipay/v1/orders", request, Order.class, idempotencyKey);
    }

    public List<Order> orders(String subject, int page, int size) {
        return get("/internal/minipay/v1/orders", uri -> uri.queryParam("subject", subject)
                        .queryParam("page", page).queryParam("size", size),
                new ParameterizedTypeReference<>() { });
    }

    public Order order(String providerOrderRef, String subject) {
        String path = "/internal/minipay/v1/orders/" + providerOrderRef;
        return get(path, uri -> uri.queryParam("subject", subject),
                new ParameterizedTypeReference<>() { });
    }

    public void paymentSucceeded(String externalOrderNo, PaymentResult result) {
        post("/internal/minipay/v1/orders/" + externalOrderNo + "/payment-results",
                result, Object.class, null);
    }

    public void paymentClosed(String externalOrderNo, CloseResult result) {
        post("/internal/minipay/v1/orders/" + externalOrderNo + "/payment-closed",
                result, Object.class, null);
    }

    public void refundResult(String externalOrderNo, RefundResult result) {
        post("/internal/minipay/v1/orders/" + externalOrderNo + "/refund-results",
                result, Object.class, null);
    }

    public EventResult requestCancellation(String externalOrderNo, CancellationRequest request) {
        return post("/internal/minipay/v1/orders/" + externalOrderNo + "/cancellation-requests",
                request, EventResult.class, null);
    }

    private <T> T post(String path, Object request, Class<T> responseType, String idempotencyKey) {
        requireSecret();
        try {
            String body = json.writeValueAsString(request);
            Signature signature = sign("POST", path, body);
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-MiniPay-Timestamp", signature.timestamp())
                    .header("X-MiniPay-Nonce", signature.nonce())
                    .header("X-MiniPay-Signature", signature.value());
            if (idempotencyKey != null) spec.header("Idempotency-Key", idempotencyKey);
            return spec.body(body).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw upstreamProblem(exception);
        } catch (CommerceApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CommerceApplicationException(
                    "COMMERCE_YSHOP_UNAVAILABLE", "外卖服务暂时不可用");
        }
    }

    private void delete(String path) {
        requireSecret();
        try {
            Signature signature = sign("DELETE", path, "");
            client.delete().uri(path)
                    .header("X-MiniPay-Timestamp", signature.timestamp())
                    .header("X-MiniPay-Nonce", signature.nonce())
                    .header("X-MiniPay-Signature", signature.value())
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw upstreamProblem(exception);
        } catch (Exception exception) {
            throw new CommerceApplicationException(
                    "COMMERCE_YSHOP_UNAVAILABLE", "意向点餐服务暂时不可用");
        }
    }

    private <T> T get(
            String path,
            java.util.function.Function<org.springframework.web.util.UriBuilder,
                    org.springframework.web.util.UriBuilder> query,
            ParameterizedTypeReference<T> responseType) {
        requireSecret();
        try {
            Signature signature = sign("GET", path, "");
            return client.get().uri(builder -> query.apply(builder.path(path)).build())
                    .header("X-MiniPay-Timestamp", signature.timestamp())
                    .header("X-MiniPay-Nonce", signature.nonce())
                    .header("X-MiniPay-Signature", signature.value())
                    .retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw upstreamProblem(exception);
        } catch (CommerceApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CommerceApplicationException(
                    "COMMERCE_YSHOP_UNAVAILABLE", "外卖服务暂时不可用");
        }
    }

    private CommerceApplicationException upstreamProblem(RestClientResponseException exception) {
        String code = null;
        try {
            code = json.readTree(exception.getResponseBodyAsString()).path("code").asText(null);
        } catch (Exception ignored) {
            // Upstream response is intentionally reduced to a stable code.
        }
        if (code != null && code.startsWith("YSHOP_")) {
            return new CommerceApplicationException(code, "意向点餐账号绑定未完成");
        }
        return new CommerceApplicationException(
                "COMMERCE_YSHOP_UNAVAILABLE", "意向点餐服务暂时不可用");
    }

    private Signature sign(String method, String path, String body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String canonical = timestamp + "\n" + nonce + "\n" + method + "\n" + path
                + "\n" + sha256(body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return new Signature(timestamp, nonce, HexFormat.of().formatHex(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void requireSecret() {
        if (secret.length < 32) throw new CommerceApplicationException(
                "COMMERCE_YSHOP_NOT_CONFIGURED", "外卖服务尚未配置");
    }

    private record Signature(String timestamp, String nonce, String value) { }
}
