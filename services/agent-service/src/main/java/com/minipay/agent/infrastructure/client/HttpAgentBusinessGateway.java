package com.minipay.agent.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.service.AgentApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public final class HttpAgentBusinessGateway implements AgentBusinessGateway {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final String identityBaseUrl;
    private final String walletBaseUrl;
    private final String paymentBaseUrl;
    private final String commerceBaseUrl;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public HttpAgentBusinessGateway(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${minipay.agent.downstream.identity-base-url}") String identityBaseUrl,
            @Value("${minipay.agent.downstream.wallet-base-url}") String walletBaseUrl,
            @Value("${minipay.agent.downstream.payment-base-url}") String paymentBaseUrl,
            @Value("${minipay.agent.downstream.commerce-base-url}") String commerceBaseUrl,
            @Value("${minipay.agent.downstream.timeout:5s}") Duration timeout) {
        this.webClient = builder.build();
        this.objectMapper = objectMapper;
        this.identityBaseUrl = identityBaseUrl;
        this.walletBaseUrl = walletBaseUrl;
        this.paymentBaseUrl = paymentBaseUrl;
        this.commerceBaseUrl = commerceBaseUrl;
        this.timeout = timeout;
    }

    @Override
    public Map<String, Object> resolveRecipient(String token, String exactMobile) {
        return post(identityBaseUrl + "/internal/v1/agent/recipients/resolve-exact-mobile",
                token, null, Map.of("mobile", exactMobile));
    }

    @Override
    public List<Map<String, Object>> resolveExactFriend(String token, String query) {
        return exchangeList(webClient.post()
                .uri(identityBaseUrl + "/internal/v1/agent/contacts/resolve-exact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("query", query)));
    }

    @Override
    public Map<String, Object> walletSummary(String token) {
        return get(walletBaseUrl + "/internal/v1/agent/wallet-summary", token);
    }

    @Override
    public Map<String, Object> aggregateBills(String token, Instant from, Instant to) {
        URI uri = UriComponentsBuilder.fromUriString(walletBaseUrl)
                .path("/internal/v1/agent/wallet-bill-aggregations")
                .queryParam("from", from)
                .queryParam("to", to)
                .build()
                .encode()
                .toUri();
        return get(uri, token);
    }

    @Override
    public Map<String, Object> listBills(String token, Instant from, Instant to,
                                         String direction, String businessType, String status,
                                         int page, int size) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(walletBaseUrl)
                .path("/internal/v1/agent/wallet-bills")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", page)
                .queryParam("size", size);
        if (direction != null) uri.queryParam("direction", direction);
        if (businessType != null) uri.queryParam("businessType", businessType);
        if (status != null) uri.queryParam("status", status);
        return get(uri.build().encode().toUri(), token);
    }

    @Override
    public Map<String, Object> prepareTransfer(
            String token,
            String idempotencyKey,
            UUID receiverUserId,
            long amountCent,
            String remark) {
        return post(paymentBaseUrl + "/internal/v1/agent/transfer-intents", token,
                idempotencyKey, Map.of(
                        "receiverUserId", receiverUserId,
                        "amountCent", amountCent,
                        "remark", remark == null ? "" : remark));
    }

    @Override
    public Map<String, Object> transferOrder(String token, UUID transferId) {
        return get(paymentBaseUrl + "/internal/v1/agent/transfer-orders/" + transferId, token);
    }

    @Override
    public List<Map<String, Object>> searchNearbyStores(
            String token, UUID locationContextId, UUID addressRefId, String fulfillmentType) {
        String locationQuery = locationContextId != null
                ? "locationContextId=" + locationContextId
                : "addressRefId=" + addressRefId;
        return exchangeList(webClient.get().uri(java.net.URI.create(
                commerceBaseUrl + "/internal/v2/agent/food/stores?" + locationQuery
                    + "&fulfillmentType=" + encode(fulfillmentType)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Override
    public List<Map<String, Object>> getStoreMenu(String token, UUID storeRefId) {
        return exchangeList(webClient.get().uri(commerceBaseUrl
                        + "/internal/v2/agent/food/stores/" + storeRefId + "/menu")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Override
    public Map<String, Object> getFoodCart(String token, UUID storeRefId) {
        return get(commerceBaseUrl + "/internal/v2/agent/food/carts/" + storeRefId, token);
    }

    @Override
    public Map<String, Object> updateFoodCart(
            String token,
            UUID storeRefId,
            UUID skuRefId,
            int quantity,
            Long expectedCartVersion) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("quantity", quantity);
        body.put("expectedVersion", expectedCartVersion);
        try {
            Map<String, Object> result = webClient.put().uri(commerceBaseUrl
                            + "/internal/v2/agent/food/carts/" + storeRefId + "/items/" + skuRefId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .bodyValue(body).retrieve().bodyToMono(MAP_TYPE).timeout(timeout).block();
            if (result == null) throw new IllegalStateException("Empty downstream response");
            return result;
        } catch (RuntimeException exception) {
            throw mapDownstream(exception);
        }
    }

    @Override
    public List<Map<String, Object>> listFoodAddresses(String token) {
        return exchangeList(webClient.get().uri(commerceBaseUrl
                        + "/internal/v2/agent/food/addresses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Override
    public Map<String, Object> prepareFoodCheckout(
            String token, UUID storeRefId, UUID addressRefId, String fulfillmentType) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("storeRefId", storeRefId);
        body.put("addressRefId", addressRefId);
        body.put("fulfillmentType", fulfillmentType);
        return post(commerceBaseUrl + "/internal/v2/agent/food/checkout-quotes", token,
                null, body);
    }

    @Override
    public Map<String, Object> getFoodOrder(String token, UUID orderRefId) {
        return get(commerceBaseUrl + "/internal/v2/agent/food/orders/" + orderRefId, token);
    }

    @Override
    public Map<String, Object> prepareFoodCancellation(String token, UUID orderRefId) {
        return get(commerceBaseUrl + "/internal/v2/agent/food/orders/" + orderRefId
                + "/cancellation-preview", token);
    }

    private Map<String, Object> get(String url, String token) {
        return exchangeMap(webClient.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Map<String, Object> get(URI uri, String token) {
        return exchangeMap(webClient.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Map<String, Object> post(
            String url, String token, String idempotencyKey, Object body) {
        WebClient.RequestBodySpec request = webClient.post().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return exchangeMap(request.bodyValue(body));
    }

    private Map<String, Object> exchangeMap(WebClient.RequestHeadersSpec<?> request) {
        try {
            Map<String, Object> result = request.retrieve().bodyToMono(MAP_TYPE).timeout(timeout).block();
            if (result == null) throw new IllegalStateException("Empty downstream response");
            return result;
        } catch (RuntimeException exception) {
            throw mapDownstream(exception);
        }
    }

    private List<Map<String, Object>> exchangeList(WebClient.RequestHeadersSpec<?> request) {
        try {
            List<Map<String, Object>> result = request.retrieve().bodyToMono(LIST_TYPE).timeout(timeout).block();
            return result == null ? List.of() : result;
        } catch (RuntimeException exception) {
            throw mapDownstream(exception);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    AgentApplicationException mapDownstream(RuntimeException exception) {
        if (exception instanceof WebClientResponseException response) {
            String downstreamCode = null;
            String downstreamDetail = null;
            try {
                Map<?, ?> problem = objectMapper.readValue(response.getResponseBodyAsByteArray(), Map.class);
                downstreamCode = problem.get("code") == null ? null : String.valueOf(problem.get("code"));
                downstreamDetail = problem.get("detail") == null ? null : String.valueOf(problem.get("detail"));
            } catch (Exception ignored) {
                // Never expose an unparsed downstream response.
            }
            int status = response.getStatusCode().value();
            if (status == 401) {
                return new AgentApplicationException("AGENT_DOWNSTREAM_AUTHENTICATION_EXPIRED",
                        "登录状态已失效，请重新登录后重试");
            }
            if (status == 403) {
                return new AgentApplicationException("AGENT_DOWNSTREAM_ACCESS_DENIED",
                        "当前业务能力暂不可用，请稍后重试");
            }
            if (status == 404) {
                return new AgentApplicationException(
                        downstreamCode == null ? "AGENT_RESOURCE_NOT_FOUND" : downstreamCode,
                        downstreamDetail == null ? "未找到对应的业务记录" : downstreamDetail);
            }
            if (status == 400 || status == 409 || status == 422 || status == 429) {
                return new AgentApplicationException(
                        downstreamCode == null ? "AGENT_DOWNSTREAM_REQUEST_REJECTED" : downstreamCode,
                        downstreamDetail == null ? "业务请求未通过校验，请检查后重试" : downstreamDetail);
            }
        }
        return new AgentApplicationException(
                "AGENT_TOOL_UNAVAILABLE", "权威业务服务暂时不可用，请稍后重试");
    }
}
