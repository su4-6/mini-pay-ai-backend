package com.minipay.commerce.infrastructure.security;

import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.uuidToBytes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ServiceHmacFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final byte[] secret;

    public ServiceHmacFilter(
            JdbcTemplate jdbc,
            @Value("${minipay.commerce.yshop.hmac-secret:}") String secret) {
        this.jdbc = jdbc;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/internal/v1/food-handoffs/consume")
                || path.startsWith("/internal/v1/food-location-contexts/")
                || path.startsWith("/internal/v1/yshop/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (secret.length < 32) { reject(response, 503, "COMMERCE_HMAC_NOT_CONFIGURED"); return; }
        String timestamp = request.getHeader("X-MiniPay-Timestamp");
        String nonce = request.getHeader("X-MiniPay-Nonce");
        String supplied = request.getHeader("X-MiniPay-Signature");
        if (timestamp == null || nonce == null || supplied == null || nonce.length() > 96) {
            reject(response, 401, "COMMERCE_SIGNATURE_REQUIRED"); return;
        }
        long epoch;
        try { epoch = Long.parseLong(timestamp); }
        catch (NumberFormatException exception) { reject(response, 401, "COMMERCE_TIMESTAMP_INVALID"); return; }
        Instant now = Instant.now();
        if (Math.abs(now.getEpochSecond() - epoch) > 300) {
            reject(response, 401, "COMMERCE_TIMESTAMP_EXPIRED"); return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String canonical = timestamp + "\n" + nonce + "\n" + request.getMethod()
                + "\n" + request.getRequestURI() + "\n" + sha256(body);
        byte[] actual;
        try { actual = HexFormat.of().parseHex(supplied); }
        catch (IllegalArgumentException exception) { reject(response, 401, "COMMERCE_SIGNATURE_INVALID"); return; }
        if (!MessageDigest.isEqual(hmac(canonical), actual)) {
            reject(response, 401, "COMMERCE_SIGNATURE_INVALID"); return;
        }
        try {
            jdbc.update("""
                    INSERT INTO food_service_nonce(nonce, expires_at, created_at)
                    VALUES (?, ?, ?)
                    """, nonce, java.sql.Timestamp.from(now.plusSeconds(300)), java.sql.Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            reject(response, 409, "COMMERCE_NONCE_REPLAYED"); return;
        }
        chain.doFilter(new BodyRequest(request, body), response);
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"" + code + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}");
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body.clone(); }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
