package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Miling (米灵) AI surface. The browser contract stays {@code /api/v1/ai/**} while the upstream
 * service owns {@code /api/v1/chat/**} with identical field and event names.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiProxyController {

    private static final String DEFAULT_MESSAGE_LIMIT = "50";

    private final ConsumerSessionService sessions;

    public AiProxyController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/conversations")
    public Mono<ResponseEntity<String>> conversations(
            WebSession session,
            ServerWebExchange exchange,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.GET,
                        "/api/v1/chat/conversations",
                        pageQuery(cursor, limit),
                        null,
                        null)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/conversations")
    public Mono<ResponseEntity<String>> createConversation(
            WebSession session,
            ServerWebExchange exchange,
            @RequestBody(required = false) CreateConversationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request != null && request.title() != null && !request.title().isBlank()) {
            body.put("title", request.title());
        }
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.POST,
                        "/api/v1/chat/conversations",
                        Map.of(),
                        body,
                        RequestIds.of(exchange))
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Mono<ResponseEntity<String>> messages(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.GET,
                        "/api/v1/chat/conversations/" + conversationId + "/messages",
                        pageQuery(cursor, limit == null ? DEFAULT_MESSAGE_LIMIT : limit),
                        null,
                        null)
                .map(UpstreamResponses::toEntity);
    }

    /**
     * Sends a user message and starts a run.
     *
     * <p>上游（米灵 trigger 端）的请求体字段是 {@code content}，浏览器也用 {@code content}，
     * 两边同名直传即可。曾经这里写的是 {@code message}（凭印象假设的上游契约），
     * 线上表现为 {@code 400 MILING_REQUEST_INVALID: content 不能为空} ——
     * 米灵对话整条链路不可用。契约以后端 Controller 的 {@code MilingChatDTOs} 为准。
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public Mono<ResponseEntity<String>> sendMessage(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String conversationId,
            @RequestBody SendMessageRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", request.content());
        body.put("clientMessageId",
                request.clientMessageId() == null || request.clientMessageId().isBlank()
                        ? UUID.randomUUID().toString() : request.clientMessageId());
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.POST,
                        "/api/v1/chat/conversations/" + conversationId + "/messages",
                        Map.of(),
                        body,
                        UUID.randomUUID().toString())
                .map(UpstreamResponses::toEntity);
    }

    /**
     * SSE passthrough. {@code X-Accel-Buffering: no} keeps reverse proxies from buffering the model
     * stream, and the stream itself is never aggregated.
     */
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runEvents(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String runId) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponse().getHeaders().set(HttpHeaders.CONNECTION, "keep-alive");
        return sessions.streamEvents(session, exchange, "/api/v1/chat/runs/" + runId + "/events");
    }

    private static Map<String, String> pageQuery(String cursor, String limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("cursor", cursor == null ? "" : cursor);
        query.put("limit", limit == null || limit.isBlank() ? "20" : limit);
        return query;
    }

    public record CreateConversationRequest(String title) {
    }

    public record SendMessageRequest(String content, String clientMessageId) {
    }
}
