package com.minipay.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.service.UuidV7;
import com.minipay.payment.infrastructure.security.MerchantSecretCipher;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers merchant callbacks independently of dashboard projection consumption. */
@Component
public class MerchantNotificationDispatcher {
    private static final int MAX_AUTOMATIC_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MerchantSecretCipher secrets;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public MerchantNotificationDispatcher(JdbcTemplate jdbc, ObjectMapper json, MerchantSecretCipher secrets) {
        this.jdbc = jdbc;
        this.json = json;
        this.secrets = secrets;
    }

    @Scheduled(fixedDelayString = "${minipay.merchant.notification-dispatch-delay:5000}")
    public void dispatchDueNotifications() {
        List<NotificationRow> due = jdbc.query("""
                SELECT n.notification_id, n.event_id, n.type, n.event_type, n.merchant_order_no, n.business_no,
                       n.refund_business_no, n.amount_cent, n.occurred_at, n.created_at, n.attempts, a.app_id,
                       a.app_secret_ciphertext, a.notify_url, a.refund_notify_url
                FROM merchant_notification n JOIN merchant_application a ON a.application_id = n.application_id
                WHERE n.status IN ('PENDING', 'RETRYING') AND n.next_attempt_at <= UTC_TIMESTAMP(6)
                      AND a.status = 'ACTIVE'
                ORDER BY n.next_attempt_at LIMIT 20
                """, (rs, ignored) -> new NotificationRow(uuid(rs.getBytes("notification_id")), uuid(rs.getBytes("event_id")).toString(),
                rs.getString("type"), rs.getString("event_type"), rs.getString("merchant_order_no"),
                rs.getString("business_no"), rs.getString("refund_business_no"), rs.getLong("amount_cent"),
                notificationOccurredAt(rs), rs.getInt("attempts"), rs.getString("app_id"),
                rs.getBytes("app_secret_ciphertext"), rs.getString("notify_url"), rs.getString("refund_notify_url")));
        due.forEach(row -> dispatch(row, true));
    }

    /** Performs one manual attempt immediately without resetting the accumulated attempt count. */
    public boolean retryManually(UUID notificationId) {
        NotificationRow row = findNotification(notificationId);
        if (row == null) return false;
        int changed = jdbc.update("""
                UPDATE merchant_notification SET status = 'PENDING', next_attempt_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE notification_id = ? AND status = 'FAILED'
                """, uuidBytes(notificationId));
        if (changed != 1) return false;
        dispatch(row, false);
        return true;
    }

    private NotificationRow findNotification(UUID notificationId) {
        List<NotificationRow> rows = jdbc.query("""
                SELECT n.notification_id, n.event_id, n.type, n.event_type, n.merchant_order_no, n.business_no,
                       n.refund_business_no, n.amount_cent, n.occurred_at, n.created_at, n.attempts, a.app_id,
                       a.app_secret_ciphertext, a.notify_url, a.refund_notify_url
                FROM merchant_notification n JOIN merchant_application a ON a.application_id = n.application_id
                WHERE n.notification_id = ? AND a.status = 'ACTIVE'
                """, (rs, ignored) -> mapNotification(rs), uuidBytes(notificationId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void dispatch(NotificationRow row, boolean automated) {
        int attempt = row.attempts() + 1;
        String callbackUrl = "REFUND".equals(row.type()) ? row.refundNotifyUrl() : row.notifyUrl();
        int claimed = jdbc.update("""
                UPDATE merchant_notification SET status = 'DELIVERING', attempts = ?, request_summary = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE notification_id = ? AND status IN ('PENDING', 'RETRYING') AND attempts = ?
                      AND next_attempt_at <= UTC_TIMESTAMP(6)
                """, attempt, summary(callbackUrl), uuidBytes(row.notificationId()), row.attempts());
        if (claimed != 1) return;
        DeliveryResult result;
        try { result = deliver(row, callbackUrl); }
        catch (Exception exception) { result = new DeliveryResult(null, "ERROR:" + exception.getClass().getSimpleName(), false); }
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO merchant_notification_attempt (
                  attempt_id, notification_id, attempt_no, automated, http_status,
                  result, request_digest, response_digest,
                  request_summary, response_summary, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, uuidBytes(UuidV7.generate()), uuidBytes(row.notificationId()), attempt, automated,
                result.httpStatus(), result.success() ? "SUCCEEDED" : "FAILED",
                callbackUrl == null ? null : MerchantRepository.hash(callbackUrl),
                result.response() == null ? null : MerchantRepository.hash(result.response()),
                summary(callbackUrl), safeSummary(result.response()), now);
        if (result.success()) {
            jdbc.update("UPDATE merchant_notification SET status = 'SUCCEEDED', next_attempt_at = NULL, response_status = ?, response_summary = ?, updated_at = ? WHERE notification_id = ? AND status = 'DELIVERING'",
                    result.httpStatus(), safeSummary(result.response()), now, uuidBytes(row.notificationId()));
        } else if (attempt >= MAX_AUTOMATIC_ATTEMPTS) {
            jdbc.update("UPDATE merchant_notification SET status = 'FAILED', next_attempt_at = NULL, response_status = ?, response_summary = ?, updated_at = ? WHERE notification_id = ? AND status = 'DELIVERING'",
                    result.httpStatus(), safeSummary(result.response()), now, uuidBytes(row.notificationId()));
        } else {
            Instant next = now.plusSeconds(Math.min(3600, 1L << Math.min(12, attempt)));
            jdbc.update("UPDATE merchant_notification SET status = 'RETRYING', next_attempt_at = ?, response_status = ?, response_summary = ?, updated_at = ? WHERE notification_id = ? AND status = 'DELIVERING'",
                    next, result.httpStatus(), safeSummary(result.response()), now, uuidBytes(row.notificationId()));
        }
    }

    private DeliveryResult deliver(NotificationRow row, String callbackUrl) throws Exception {
        if (callbackUrl == null || callbackUrl.isBlank()) return new DeliveryResult(null, "NO_CALLBACK_URL", false);
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", row.eventId()); payload.put("eventType", row.eventType()); payload.put("appId", row.appId());
        payload.put("merchantOrderNo", row.merchantOrderNo());
        payload.put("paymentOrderNo", row.businessNo());
        payload.put("businessNo", row.businessNo());
        payload.put("amountCent", row.amountCent());
        if (row.refundBusinessNo() != null) {
            payload.put("refundNo", row.refundBusinessNo());
            payload.put("refundBusinessNo", row.refundBusinessNo());
        }
        payload.put("occurredAt", row.occurredAt().toString()); payload.put("timestamp", timestamp); payload.put("nonce", nonce);
        String signatureSource = signingSource(row.appId(), row.merchantOrderNo(), row.refundBusinessNo(),
                row.businessNo(), row.eventType(), row.amountCent(), timestamp, nonce);
        String signature = hmac(secrets.decrypt(row.secretCiphertext()), signatureSource);
        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("X-MiniPay-Timestamp", timestamp)
                .header("X-MiniPay-Nonce", nonce).header("X-MiniPay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new DeliveryResult(response.statusCode(), response.body(), response.statusCode() == 200 && successBody(response.body()));
    }

    private boolean successBody(String body) {
        try { JsonNode node = json.readTree(body); return "SUCCESS".equals(node.path("code").asText()); }
        catch (Exception exception) { return false; }
    }
    static String signingSource(String appId, String merchantOrderNo, String refundBusinessNo,
            String businessNo, String eventType, long amountCent, String timestamp, String nonce) {
        return refundBusinessNo == null
                ? String.join("\n", "PAYMENT", appId, merchantOrderNo, businessNo,
                        Long.toString(amountCent), timestamp, nonce)
                : String.join("\n", "REFUND", appId, merchantOrderNo,
                        refundBusinessNo, businessNo, Long.toString(amountCent),
                        timestamp, nonce);
    }
    private static String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String summary(String url) {
        if (url == null || url.isBlank()) return "NO_CALLBACK_URL";
        try { URI uri = URI.create(url); return "POST " + uri.getScheme() + "://" + uri.getHost() + uri.getPath(); }
        catch (RuntimeException exception) { return "INVALID_CALLBACK_URL"; }
    }
    private static String safeSummary(String value) { return value == null ? null : value.substring(0, Math.min(512, value.length())); }
    private static UUID uuid(byte[] value) { ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    private static NotificationRow mapNotification(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotificationRow(uuid(rs.getBytes("notification_id")), uuid(rs.getBytes("event_id")).toString(),
                rs.getString("type"), rs.getString("event_type"), rs.getString("merchant_order_no"),
                rs.getString("business_no"), rs.getString("refund_business_no"), rs.getLong("amount_cent"),
                notificationOccurredAt(rs), rs.getInt("attempts"), rs.getString("app_id"),
                rs.getBytes("app_secret_ciphertext"), rs.getString("notify_url"), rs.getString("refund_notify_url"));
    }
    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static Instant notificationOccurredAt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return notificationOccurredAt(rs.getTimestamp("occurred_at"), rs.getTimestamp("created_at"));
    }
    static Instant notificationOccurredAt(java.sql.Timestamp occurredAt, java.sql.Timestamp createdAt) {
        return (occurredAt == null ? createdAt : occurredAt).toInstant();
    }
    private record DeliveryResult(Integer httpStatus, String response, boolean success) { }
    private record NotificationRow(UUID notificationId, String eventId, String type, String eventType, String merchantOrderNo,
                                   String businessNo, String refundBusinessNo, long amountCent, Instant occurredAt, int attempts,
                                   String appId, byte[] secretCiphertext, String notifyUrl, String refundNotifyUrl) { }
}
