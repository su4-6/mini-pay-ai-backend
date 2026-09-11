package co.yixiang.yshop.module.minipay.service;

import static co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.AddressLocationDraftView;
import static co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.CreatedAddressView;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class MiniPayAddressService {
    private final JdbcTemplate jdbc;
    private final MiniPayLocationService locations;
    private final RestClient amap;
    private final String amapKey;
    private final boolean allowGenericAddress;
    private final long tenantId;

    public MiniPayAddressService(
            JdbcTemplate jdbc,
            MiniPayLocationService locations,
            @Value("${yshop.minipay.amap-reverse-geocode-key:}") String amapKey,
            @Value("${yshop.minipay.allow-generic-address:false}") boolean allowGenericAddress,
            @Value("${yshop.minipay.tenant-id:1}") long tenantId) {
        this.jdbc = jdbc;
        this.locations = locations;
        this.amap = RestClient.builder().baseUrl("https://restapi.amap.com").build();
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.allowGenericAddress = allowGenericAddress;
        this.tenantId = tenantId;
    }

    public AddressLocationDraftView createDraft(
            long memberId, String subject, String locationContextId) {
        MiniPayLocationService.ResolvedLocation location =
                locations.resolve(subject, locationContextId);
        AddressComponents address = reverseGeocode(location.longitude(), location.latitude());
        UUID draftId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO yshop_minipay_address_location_draft
                  (draft_id, subject, member_id, location_context_id, display_address,
                   province, city, district, longitude, latitude, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                """, draftId.toString(), subject, memberId, locationContextId,
                address.displayAddress(), address.province(), address.city(), address.district(),
                BigDecimal.valueOf(location.longitude()), BigDecimal.valueOf(location.latitude()),
                expiresAt);
        return new AddressLocationDraftView(
                draftId.toString(), address.displayAddress(), expiresAt.toString());
    }

    @Transactional
    public CreatedAddressView createAddress(
            long memberId,
            String subject,
            String draftId,
            String recipient,
            String phone,
            String detail,
            boolean defaultAddress,
            String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw problem("MINIPAY_IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        List<CreatedAddressView> replay = jdbc.query("""
                SELECT address_id, display_address, is_default
                  FROM yshop_minipay_address_location_draft
                 WHERE subject = ? AND idempotency_key = ? AND address_id IS NOT NULL
                """, (rs, ignored) -> new CreatedAddressView(
                rs.getLong("address_id"), rs.getString("display_address"),
                rs.getBoolean("is_default")), subject, idempotencyKey);
        if (!replay.isEmpty()) return replay.get(0);

        List<DraftRow> rows = jdbc.query("""
                SELECT draft_id, subject, member_id, display_address, province, city, district,
                       longitude, latitude, expires_at, consumed_at
                  FROM yshop_minipay_address_location_draft
                 WHERE draft_id = ? FOR UPDATE
                """, (rs, ignored) -> new DraftRow(
                rs.getString("draft_id"), rs.getString("subject"), rs.getLong("member_id"),
                rs.getString("display_address"), rs.getString("province"), rs.getString("city"),
                rs.getString("district"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null
                        : rs.getTimestamp("consumed_at").toInstant()), draftId);
        if (rows.isEmpty()) throw problem("MINIPAY_ADDRESS_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND);
        DraftRow draft = rows.get(0);
        if (!draft.subject().equals(subject) || draft.memberId() != memberId) {
            throw problem("MINIPAY_ADDRESS_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (draft.consumedAt() != null || !draft.expiresAt().isAfter(Instant.now())) {
            throw problem("MINIPAY_ADDRESS_DRAFT_EXPIRED", HttpStatus.CONFLICT);
        }

        if (defaultAddress) {
            jdbc.update("""
                    UPDATE yshop_user_address SET is_default = 0, updater = 'minipay', update_time = NOW(6)
                     WHERE uid = ? AND deleted = b'0' AND tenant_id = ?
                    """, memberId, tenantId);
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO yshop_user_address
                      (uid, real_name, address, phone, province, city, city_id, district,
                       detail, post_code, longitude, latitude, is_default, deleted,
                       creator, create_time, updater, update_time, tenant_id)
                    VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, '', ?, ?, ?, b'0',
                            'minipay', NOW(6), 'minipay', NOW(6), ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setString(2, recipient.trim());
            statement.setString(3, draft.displayAddress());
            statement.setString(4, phone);
            statement.setString(5, draft.province());
            statement.setString(6, draft.city());
            statement.setString(7, draft.district());
            statement.setString(8, detail.trim());
            statement.setString(9, draft.longitude().toPlainString());
            statement.setString(10, draft.latitude().toPlainString());
            statement.setInt(11, defaultAddress ? 1 : 0);
            statement.setLong(12, tenantId);
            return statement;
        }, key);
        long addressId = key.getKey().longValue();
        int updated = jdbc.update("""
                UPDATE yshop_minipay_address_location_draft
                   SET consumed_at = NOW(6), idempotency_key = ?, address_id = ?, is_default = ?
                 WHERE draft_id = ? AND consumed_at IS NULL
                """, idempotencyKey, addressId, defaultAddress, draftId);
        if (updated != 1) throw problem("MINIPAY_ADDRESS_DRAFT_EXPIRED", HttpStatus.CONFLICT);
        return new CreatedAddressView(addressId, draft.displayAddress(), defaultAddress);
    }

    private AddressComponents reverseGeocode(double longitude, double latitude) {
        if (amapKey.isBlank()) {
            if (allowGenericAddress) return new AddressComponents("当前位置", "", "", "");
            throw problem("MINIPAY_REVERSE_GEOCODER_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            JsonNode response = amap.get().uri(builder -> builder
                    .path("/v3/geocode/regeo")
                    .queryParam("key", amapKey)
                    .queryParam("location", longitude + "," + latitude)
                    .queryParam("extensions", "base")
                    .build()).retrieve().body(JsonNode.class);
            JsonNode regeocode = response == null ? null : response.path("regeocode");
            String display = text(regeocode, "formatted_address");
            JsonNode component = regeocode == null ? null : regeocode.path("addressComponent");
            if (display.isBlank()) throw new IllegalStateException("Empty reverse-geocode response");
            return new AddressComponents(display, text(component, "province"),
                    text(component, "city"), text(component, "district"));
        } catch (MiniPayProblem problem) {
            throw problem;
        } catch (Exception ignored) {
            if (allowGenericAddress) return new AddressComponents("当前位置", "", "", "");
            throw problem("MINIPAY_REVERSE_GEOCODER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null || parent.isMissingNode()) return "";
        JsonNode value = parent.path(field);
        return value.isTextual() ? value.asText("") : "";
    }

    private static MiniPayProblem problem(String code, HttpStatus status) {
        return new MiniPayProblem(code, status);
    }

    private record AddressComponents(
            String displayAddress, String province, String city, String district) { }

    private record DraftRow(
            String draftId, String subject, long memberId, String displayAddress,
            String province, String city, String district, BigDecimal longitude,
            BigDecimal latitude, Instant expiresAt, Instant consumedAt) { }
}
