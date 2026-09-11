package co.yixiang.yshop.module.minipay.service;

import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApi;
import co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO;
import co.yixiang.yshop.module.system.enums.oauth2.OAuth2ClientConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MiniPayHandoffLoginService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OAuth2TokenApi tokens;
    private final RestClient commerce;
    private final byte[] secret;
    private final String allowedOrigin;
    private final long tenantId;

    public MiniPayHandoffLoginService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            OAuth2TokenApi tokens,
            @Value("${yshop.minipay.commerce-base-url:http://localhost:8085}") String commerceBaseUrl,
            @Value("${yshop.minipay.hmac-secret:}") String secret,
            @Value("${yshop.minipay.h5-origin:https://food.minipay.local}") String allowedOrigin,
            @Value("${yshop.minipay.tenant-id:1}") long tenantId) {
        this.jdbc = jdbc;
        this.json = json;
        this.tokens = tokens;
        this.commerce = RestClient.builder().baseUrl(commerceBaseUrl).build();
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigin = allowedOrigin;
        this.tenantId = tenantId;
    }

    public LoginView login(String code, String deviceProof, String requestOrigin) {
        if (!allowedOrigin.equals(requestOrigin)) {
            throw new MiniPayProblem("MINIPAY_H5_ORIGIN_INVALID", HttpStatus.UNAUTHORIZED);
        }
        if (secret.length < 32) {
            throw new MiniPayProblem("MINIPAY_HMAC_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        String subject = consume(code, deviceProof, requestOrigin);
        List<Long> members = jdbc.query("""
                SELECT member_id FROM yshop_minipay_external_identity
                 WHERE provider = 'MINIPAY' AND subject = ?
                """, (rs, ignored) -> rs.getLong(1), subject);
        if (members.isEmpty()) {
            throw new MiniPayProblem("MINIPAY_IDENTITY_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            OAuth2AccessTokenRespDTO token = tokens.createAccessToken(
                    new OAuth2AccessTokenCreateReqDTO()
                            .setUserId(members.get(0))
                            .setUserType(UserTypeEnum.MEMBER.getValue())
                            .setClientId(OAuth2ClientConstants.CLIENT_ID_MINIPAY_FOOD_H5));
            return new LoginView(token.getUserId(), token.getAccessToken(), token.getRefreshToken(),
                    token.getExpiresTime());
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
    }

    private String consume(String code, String deviceProof, String origin) {
        try {
            String path = "/internal/v1/food-handoffs/consume";
            String body = json.writeValueAsString(Map.of(
                    "code", code, "deviceProof", deviceProof, "origin", origin));
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String canonical = timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + sha256(body);
            HandoffIdentity identity = commerce.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-MiniPay-Timestamp", timestamp)
                    .header("X-MiniPay-Nonce", nonce)
                    .header("X-MiniPay-Signature", hmac(canonical))
                    .body(body).retrieve().body(HandoffIdentity.class);
            if (identity == null || identity.subject() == null) throw new IllegalStateException();
            return identity.subject();
        } catch (MiniPayProblem problem) {
            throw problem;
        } catch (Exception exception) {
            throw new MiniPayProblem("MINIPAY_HANDOFF_INVALID", HttpStatus.UNAUTHORIZED);
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

    public record LoginView(long userId, String accessToken, String refreshToken, LocalDateTime expiresAt) { }
    private record HandoffIdentity(String subject) { }
}
