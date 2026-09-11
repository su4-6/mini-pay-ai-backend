package co.yixiang.yshop.module.minipay.service;

import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.StoreView;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MiniPayLocationService {
    private final MiniPayFoodService food;
    private final ObjectMapper json;
    private final RestClient commerce;
    private final byte[] secret;

    public MiniPayLocationService(
            MiniPayFoodService food,
            ObjectMapper json,
            @Value("${yshop.minipay.commerce-base-url:http://localhost:8085}") String baseUrl,
            @Value("${yshop.minipay.hmac-secret:}") String secret) {
        this.food = food;
        this.json = json;
        this.commerce = RestClient.builder().baseUrl(baseUrl).build();
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public List<StoreView> nearby(
            String subject, String locationContextId, String fulfillmentType, double radiusKm) {
        ResolvedLocation location = resolve(subject, locationContextId);
        return food.nearbyStores(
                location.longitude(), location.latitude(), fulfillmentType, radiusKm);
    }

    public static void requireExactlyOneSource(String locationContextId, Long addressId) {
        boolean hasContext = locationContextId != null && !locationContextId.isBlank();
        boolean hasAddress = addressId != null;
        if (hasContext == hasAddress) {
            throw new MiniPayProblem("MINIPAY_LOCATION_SOURCE_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    public ResolvedLocation resolve(String subject, String locationContextId) {
        if (secret.length < 32) {
            throw new MiniPayProblem("MINIPAY_HMAC_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            UUID contextId = UUID.fromString(locationContextId);
            String path = "/internal/v1/food-location-contexts/" + contextId + "/resolve";
            String body = json.writeValueAsString(Map.of("subject", subject));
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String canonical = timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + sha256(body);
            ResolvedLocation location = commerce.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-MiniPay-Timestamp", timestamp)
                    .header("X-MiniPay-Nonce", nonce)
                    .header("X-MiniPay-Signature", hmac(canonical))
                    .body(body).retrieve().body(ResolvedLocation.class);
            if (location == null) throw new IllegalStateException("Empty location response");
            return location;
        } catch (MiniPayProblem problem) {
            throw problem;
        } catch (Exception exception) {
            throw new MiniPayProblem("MINIPAY_LOCATION_CONTEXT_INVALID", HttpStatus.UNAUTHORIZED);
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

    public record ResolvedLocation(
            String locationContextId, double longitude, double latitude,
            Double accuracyMeters, String capturedAt, String expiresAt) { }
}
