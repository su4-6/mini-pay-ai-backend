package co.yixiang.yshop.module.minipay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Delivers approved refunds to Commerce; rows remain durable across transient failures. */
@Component
public class MiniPayRefundDispatcher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RestClient client;
    private final byte[] secret;
    private final boolean enabled;

    public MiniPayRefundDispatcher(
            JdbcTemplate jdbc,
            ObjectMapper json,
            @Value("${yshop.minipay.commerce-base-url:}") String baseUrl,
            @Value("${yshop.minipay.hmac-secret:}") String secret) {
        this.jdbc = jdbc;
        this.json = json;
        this.enabled = baseUrl != null && !baseUrl.isBlank() && secret != null && secret.length() >= 32;
        this.client = enabled ? RestClient.builder().baseUrl(baseUrl).build() : null;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Scheduled(fixedDelayString = "${yshop.minipay.refund-outbox-delay-ms:3000}")
    public void dispatch() {
        if (!enabled) return;
        List<Row> rows = jdbc.query("""
                SELECT request_id, yshop_order_no, reason, attempts
                  FROM yshop_minipay_refund_outbox
                 WHERE status = 'PENDING' AND next_attempt_at <= NOW(6)
                 ORDER BY created_at LIMIT 20
                """, (rs, ignored) -> new Row(rs.getString("request_id"),
                rs.getString("yshop_order_no"), rs.getString("reason"), rs.getInt("attempts")));
        rows.forEach(this::deliver);
    }

    private void deliver(Row row) {
        int claimed = jdbc.update("""
                UPDATE yshop_minipay_refund_outbox
                   SET status = 'SENDING', attempts = attempts + 1, updated_at = NOW(6)
                 WHERE request_id = ? AND status = 'PENDING'
                """, row.requestId());
        if (claimed != 1) return;
        String path = "/internal/v1/yshop/food-orders/" + row.yshopOrderNo() + "/refunds";
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "refundRequestId", row.requestId(), "reason", row.reason()));
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String canonical = timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + sha256(body);
            client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-MiniPay-Timestamp", timestamp)
                    .header("X-MiniPay-Nonce", nonce)
                    .header("X-MiniPay-Signature", hmac(canonical))
                    .body(body).retrieve().toBodilessEntity();
            jdbc.update("""
                    UPDATE yshop_minipay_refund_outbox
                       SET status = 'SENT', updated_at = NOW(6) WHERE request_id = ?
                    """, row.requestId());
        } catch (Exception exception) {
            long delay = Math.min(300, 1L << Math.min(row.attempts() + 1, 8));
            jdbc.update("""
                    UPDATE yshop_minipay_refund_outbox
                       SET status = 'PENDING', next_attempt_at = DATE_ADD(NOW(6), INTERVAL ? SECOND),
                           updated_at = NOW(6) WHERE request_id = ?
                    """, delay, row.requestId());
        }
    }

    private String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Row(String requestId, String yshopOrderNo, String reason, int attempts) { }
}
