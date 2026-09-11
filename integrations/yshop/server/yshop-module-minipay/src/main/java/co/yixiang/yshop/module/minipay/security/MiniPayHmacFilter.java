package co.yixiang.yshop.module.minipay.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates Commerce-to-yshop calls and rejects timestamp/nonce replay. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MiniPayHmacFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/internal/minipay/";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final byte[] secret;

    public MiniPayHmacFilter(
            JdbcTemplate jdbc,
            @Value("${yshop.minipay.hmac-secret:}") String secret) {
        this.jdbc = jdbc;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (secret.length < 32) {
            reject(response, 503, "MINIPAY_HMAC_NOT_CONFIGURED");
            return;
        }
        String timestamp = request.getHeader("X-MiniPay-Timestamp");
        String nonce = request.getHeader("X-MiniPay-Nonce");
        String supplied = request.getHeader("X-MiniPay-Signature");
        if (timestamp == null || nonce == null || supplied == null || nonce.length() > 96) {
            reject(response, 401, "MINIPAY_SIGNATURE_REQUIRED");
            return;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            reject(response, 401, "MINIPAY_TIMESTAMP_INVALID");
            return;
        }
        Instant now = Instant.now();
        if (Math.abs(now.getEpochSecond() - epochSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            reject(response, 401, "MINIPAY_TIMESTAMP_EXPIRED");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String bodyHash = sha256(body);
        String canonical = timestamp + "\n" + nonce + "\n" + request.getMethod()
                + "\n" + request.getRequestURI() + "\n" + bodyHash;
        byte[] expected = hmac(canonical);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(supplied);
        } catch (IllegalArgumentException exception) {
            reject(response, 401, "MINIPAY_SIGNATURE_INVALID");
            return;
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            reject(response, 401, "MINIPAY_SIGNATURE_INVALID");
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO yshop_minipay_request_nonce(nonce, expires_at, created_at)
                    VALUES (?, ?, ?)
                    """, nonce, now.plus(MAX_CLOCK_SKEW_SECONDS, ChronoUnit.SECONDS), now);
        } catch (DuplicateKeyException exception) {
            reject(response, 409, "MINIPAY_NONCE_REPLAYED");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate MiniPay HMAC", exception);
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
