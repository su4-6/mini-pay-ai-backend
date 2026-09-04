package com.minipay.payment.interfaces.rest;

import com.minipay.payment.infrastructure.messaging.MerchantNotificationDispatcher;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/notifications")
public class OpsNotificationController {
    private final JdbcTemplate jdbc;
    private final MerchantNotificationDispatcher dispatcher;
    private final MerchantRepository merchants;
    public OpsNotificationController(JdbcTemplate jdbc, MerchantNotificationDispatcher dispatcher, MerchantRepository merchants) {
        this.jdbc = jdbc; this.dispatcher = dispatcher; this.merchants = merchants;
    }
    @GetMapping public List<NotificationResponse> list(@RequestParam(defaultValue = "20") int size) {
        return jdbc.query("SELECT notification_id, event_id, type, status, attempts, next_attempt_at, response_summary, created_at FROM merchant_notification ORDER BY created_at DESC LIMIT ?", (rs, ignored) -> new NotificationResponse(uuid(rs.getBytes("notification_id")).toString(), uuid(rs.getBytes("event_id")).toString(), rs.getString("type"), rs.getString("status"), rs.getInt("attempts"), instant(rs, "next_attempt_at"), rs.getString("response_summary"), instant(rs, "created_at")), Math.min(Math.max(size, 1), 100));
    }
    @GetMapping("/{notificationId}") public NotificationDetailResponse detail(@PathVariable UUID notificationId) {
        List<NotificationDetailResponse> rows = jdbc.query("""
                SELECT n.notification_id, n.event_id, n.type, n.status, n.attempts, n.next_attempt_at,
                       n.request_summary, n.response_summary, n.created_at
                FROM merchant_notification n WHERE n.notification_id = ?
                """, (rs, ignored) -> new NotificationDetailResponse(uuid(rs.getBytes("notification_id")).toString(),
                uuid(rs.getBytes("event_id")).toString(), rs.getString("type"), rs.getString("status"), rs.getInt("attempts"),
                instant(rs, "next_attempt_at"), rs.getString("request_summary"), rs.getString("response_summary"),
                instant(rs, "created_at"), attempts(notificationId)), bytes(notificationId));
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND");
        return rows.getFirst();
    }
    @PostMapping("/{notificationId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) public void retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {
        if (!dispatcher.retryManually(notificationId)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "NOTIFICATION_NOT_MANUALLY_RETRYABLE");
        merchants.audit(null, jwt.getSubject(), "ADMIN", "MERCHANT_NOTIFICATION_MANUAL_RETRY", "NOTIFICATION",
                notificationId.toString(), null, null, "FAILED", "PENDING");
    }
    private static UUID uuid(byte[] value) { ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
    private List<AttemptResponse> attempts(UUID notificationId) {
        return jdbc.query("""
                SELECT attempt_no, automated, http_status, result, request_summary, response_summary, occurred_at
                FROM merchant_notification_attempt WHERE notification_id = ? ORDER BY attempt_no
                """, (rs, ignored) -> new AttemptResponse(rs.getInt("attempt_no"), rs.getBoolean("automated"), (Integer) rs.getObject("http_status"),
                rs.getString("result"), rs.getString("request_summary"), rs.getString("response_summary"),
                instant(rs, "occurred_at")), bytes(notificationId));
    }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException { java.sql.Timestamp t = rs.getTimestamp(col); return t == null ? null : t.toInstant(); }
    public record NotificationResponse(String notificationId, String eventId, String type, String status, int attempts, Instant nextAttemptAt, String responseSummary, Instant createdAt) { }
    public record NotificationDetailResponse(String notificationId, String eventId, String type, String status,
                                             int attempts, Instant nextAttemptAt, String requestSummary,
                                             String responseSummary, Instant createdAt, List<AttemptResponse> history) { }
    public record AttemptResponse(int attemptNo, boolean automated, Integer httpStatus, String result, String requestSummary,
                                  String responseSummary, Instant occurredAt) { }
}
