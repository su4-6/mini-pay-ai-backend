package com.minipay.payment.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates server-to-server merchant-application API requests.
 *
 * <p>Requests must carry {@code X-MiniPay-App-Id}, {@code X-MiniPay-Timestamp} (epoch millis),
 * {@code X-MiniPay-Nonce} and {@code X-MiniPay-Signature} = hex HMAC-SHA256 over
 * {@code method\npath\nappId\ntimestamp\nonce\nbody} using the application secret.
 * Enforces application/merchant ACTIVE, IP allow list, API permission and timestamp freshness.
 */
@Component
public class MerchantApiSignatureFilter extends OncePerRequestFilter {
    private static final String BASE = "/api/v1/merchant-api/";
    private static final long MAX_SKEW_MILLIS = 300_000L;
    public static final String CONTEXT_ATTRIBUTE =
            "com.minipay.payment.infrastructure.security.MerchantApiContext";

    private final JdbcTemplate jdbc;
    private final MerchantSecretCipher secrets;
    private final ObjectMapper json;

    public MerchantApiSignatureFilter(
            JdbcTemplate jdbc, MerchantSecretCipher secrets, ObjectMapper json) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(BASE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        String appId = header(request, "X-MiniPay-App-Id");
        String timestamp = header(request, "X-MiniPay-Timestamp");
        String nonce = header(request, "X-MiniPay-Nonce");
        String signature = header(request, "X-MiniPay-Signature");
        if (appId == null || timestamp == null || nonce == null || signature == null) {
            reject(response, HttpStatus.UNAUTHORIZED, "INVALID_MERCHANT_API_SIGNATURE",
                    "缺少 X-MiniPay 签名请求头", request);
            return;
        }

        AppRow app = findApp(appId);
        if (app == null) {
            reject(response, HttpStatus.NOT_FOUND, "MERCHANT_APP_NOT_FOUND", "应用不存在", request);
            return;
        }
        if (!"ACTIVE".equals(app.appStatus()) || !"ACTIVE".equals(app.merchantStatus())) {
            reject(response, HttpStatus.FORBIDDEN, "MERCHANT_APP_DISABLED", "应用或商户已停用", request);
            return;
        }
        if (!ipAllowed(request, app.ipWhiteList())) {
            reject(response, HttpStatus.FORBIDDEN, "IP_NOT_WHITELISTED", "IP 不在应用白名单内", request);
            return;
        }
        String permission = requiredPermission(request.getMethod(), request.getRequestURI());
        if (permission == null || !contains(app.permissions(), permission)) {
            reject(response, HttpStatus.FORBIDDEN, "API_PERMISSION_DENIED", "应用无权调用该接口", request);
            return;
        }
        if (!timestampFresh(timestamp)) {
            reject(response, HttpStatus.UNAUTHORIZED, "TIMESTAMP_EXPIRED", "请求时间戳已过期", request);
            return;
        }
        String secret = secrets.decrypt(app.secretCiphertext());
        String canonical = signingSource(
                request.getMethod(), request.getRequestURI(), appId, timestamp, nonce, wrapped.body());
        if (!constantTimeEquals(signature, hmac(secret, canonical))) {
            reject(response, HttpStatus.UNAUTHORIZED, "INVALID_MERCHANT_API_SIGNATURE",
                    "签名校验失败", request);
            return;
        }

        request.setAttribute(CONTEXT_ATTRIBUTE, new MerchantApiContext(
                uuid(app.applicationId()), uuid(app.merchantId()), app.appId(),
                secret, app.permissions(), app.channels()));
        chain.doFilter(wrapped, response);
    }

    static String signingSource(
            String method, String path, String appId, String timestamp, String nonce, String body) {
        return String.join("\n", method.toUpperCase(), path, appId, timestamp, nonce, body);
    }

    private AppRow findApp(String appId) {
        return jdbc.query(
                """
                SELECT a.application_id, a.merchant_id, a.app_id, a.status AS app_status,
                       a.api_permissions, a.available_channels, a.ip_white_list,
                       a.app_secret_ciphertext, m.status AS merchant_status
                  FROM merchant_application a
                  JOIN merchant m ON m.merchant_id = a.merchant_id
                 WHERE a.app_id = ?
                """,
                (rs, ignored) -> new AppRow(
                        rs.getBytes("application_id"), rs.getBytes("merchant_id"),
                        rs.getString("app_id"), rs.getString("app_status"),
                        rs.getString("api_permissions"), rs.getString("available_channels"),
                        rs.getString("ip_white_list"), rs.getBytes("app_secret_ciphertext"),
                        rs.getString("merchant_status")),
                appId).stream().findFirst().orElse(null);
    }

    private static String requiredPermission(String method, String servletPath) {
        String rest = servletPath.startsWith(BASE)
                ? servletPath.substring(BASE.length()) : servletPath;
        String[] segments = rest.split("/");
        String first = segments.length > 0 ? segments[0] : "";
        boolean post = "POST".equalsIgnoreCase(method);
        boolean get = "GET".equalsIgnoreCase(method);
        if (post && "orders".equals(first) && segments.length == 1) {
            return "PAYMENT_CREATE";
        }
        if (get && "orders".equals(first) && segments.length == 2) {
            return "PAYMENT_QUERY";
        }
        if (post && "orders".equals(first) && segments.length == 3
                && "refunds".equals(segments[2])) {
            return "REFUND_CREATE";
        }
        if (get && "bills".equals(first) && segments.length == 1) {
            return "BILL_QUERY";
        }
        return null;
    }

    private static boolean ipAllowed(HttpServletRequest request, String whiteList) {
        if (whiteList == null || whiteList.isBlank()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return Cidr.matches(ip, Arrays.asList(whiteList.split(",")));
    }

    private static boolean timestampFresh(String timestamp) {
        try {
            long value = Long.parseLong(timestamp);
            return Math.abs(Instant.now().toEpochMilli() - value) <= MAX_SKEW_MILLIS;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("MERCHANT_API_HMAC_FAILED", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean contains(String commaSeparated, String token) {
        return commaSeparated != null
                && Arrays.stream(commaSeparated.split(","))
                .map(String::trim).anyMatch(token::equals);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void reject(
            HttpServletResponse response, HttpStatus status, String code, String detail,
            HttpServletRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.minipay.local/problems/"
                + code.toLowerCase().replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("requestId", RequestIdFilter.get(request));
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }

    private record AppRow(
            byte[] applicationId, byte[] merchantId, String appId, String appStatus,
            String permissions, String channels, String ipWhiteList,
            byte[] secretCiphertext, String merchantStatus) {
    }
}
