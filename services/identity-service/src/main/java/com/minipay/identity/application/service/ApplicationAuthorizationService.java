package com.minipay.identity.application.service;

import static com.minipay.identity.infrastructure.persistence.AdminAccountRepository.bytesToUuid;
import static com.minipay.identity.infrastructure.persistence.AdminAccountRepository.uuidToBytes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.application.service.ConsumerProfileService.ProfileView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuthorizationService {
    public static final String YSHOP_FOOD = "yshop-food";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConsumerProfileService profiles;
    private final PhoneDisclosureCipher phoneCipher;
    private final ConsumerSmsChallengeService smsChallenges;
    private final PhoneNumberService phoneNumbers;
    private final StringRedisTemplate redis;

    public ApplicationAuthorizationService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            ConsumerProfileService profiles,
            PhoneDisclosureCipher phoneCipher,
            ConsumerSmsChallengeService smsChallenges,
            PhoneNumberService phoneNumbers,
            StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.json = json;
        this.profiles = profiles;
        this.phoneCipher = phoneCipher;
        this.smsChallenges = smsChallenges;
        this.phoneNumbers = phoneNumbers;
        this.redis = redis;
    }

    public List<AuthorizationView> list(UUID userId) {
        return jdbc.query("""
                SELECT a.application_id, a.display_name, a.developer_name, a.icon_url,
                       a.privacy_policy_url, a.terms_url, a.consent_version,
                       ua.authorization_id, ua.status, ua.authorized_at, ua.last_used_at,
                       ua.revoked_at, ua.consent_version AS authorized_consent_version,
                       ua.bound_phone_masked, ua.bound_phone_ciphertext,
                       u.nickname, u.phone_masked, u.phone_ciphertext
                       , EXISTS(SELECT 1 FROM external_application_scope eas
                                WHERE eas.application_id = a.application_id
                                  AND eas.scope_code = 'profile.phone'
                                  AND eas.required_scope = TRUE) AS phone_required
                  FROM external_application a
                  LEFT JOIN user_application_authorization ua
                    ON ua.application_id = a.application_id AND ua.user_id = ?
                  JOIN user_profile u ON u.user_id = ? AND u.status = 'ACTIVE'
                 WHERE a.status = 'ACTIVE'
                 ORDER BY a.application_id
                """, (rs, ignored) -> view(rs, userId), uuidToBytes(userId), uuidToBytes(userId));
    }

    public AuthorizationView get(UUID userId, String applicationId) {
        return list(userId).stream().filter(value -> value.applicationId().equals(applicationId))
                .findFirst().orElseThrow(() -> problem("APPLICATION_NOT_FOUND"));
    }

    @Transactional
    public AuthorizationView grant(
            UUID userId,
            String applicationId,
            Set<String> requestedScopes,
            int consentVersion,
            String idempotencyKey) {
        return grant(userId, applicationId, requestedScopes, consentVersion, null, null, idempotencyKey);
    }

    @Transactional
    public AuthorizationView grant(
            UUID userId,
            String applicationId,
            Set<String> requestedScopes,
            int consentVersion,
            String phoneChallengeId,
            String verificationCode,
            String idempotencyKey) {
        requireIdempotency(idempotencyKey);
        ApplicationDefinition application = definition(applicationId);
        if (consentVersion != application.consentVersion()) {
            throw problem("CONSENT_VERSION_INVALID");
        }
        Set<String> normalized = new LinkedHashSet<>(requestedScopes == null ? Set.of() : requestedScopes);
        if (!application.allowedScopes().containsAll(normalized)
                || !normalized.containsAll(application.requiredScopes())) {
            throw problem("APPLICATION_SCOPES_INVALID");
        }
        PhoneRow phone = phone(userId);
        AuthorizationPhoneRow boundPhone = authorizationPhone(userId, applicationId);
        if (!YSHOP_FOOD.equals(applicationId)
                && normalized.contains("profile.phone") && phone.ciphertext() == null) {
            throw problem("PHONE_UPGRADE_REQUIRED");
        }
        byte[] requestHash = sha256(applicationId + "\n" + consentVersion + "\n"
                + normalized.stream().sorted().toList() + "\n"
                + (phoneChallengeId == null ? "" : phoneChallengeId));
        if (operationReplay(userId, applicationId, "GRANT", idempotencyKey, requestHash)) {
            return get(userId, applicationId);
        }

        String verifiedMobile = null;
        if (YSHOP_FOOD.equals(applicationId)) {
            String binding = phoneChallengeId == null ? null
                    : redis.opsForValue().get(challengeBindingKey(phoneChallengeId));
            boolean replacementChallenge = (userId + "\n" + applicationId).equals(binding);
            if (boundPhone == null && !replacementChallenge) {
                throw problem("APPLICATION_PHONE_VERIFICATION_REQUIRED");
            }
            if (replacementChallenge) {
                if (verificationCode == null || verificationCode.isBlank()) {
                    throw problem("APPLICATION_PHONE_VERIFICATION_REQUIRED");
                }
                try {
                    verifiedMobile = smsChallenges.consume(phoneChallengeId, verificationCode).mobile();
                    redis.delete(challengeBindingKey(phoneChallengeId));
                } catch (LoginRejectedException exception) {
                    throw problem(exception.code());
                }
            }
        }

        UUID authorizationId = authorizationId(userId, applicationId);
        if (authorizationId == null) authorizationId = UuidV7.generate();
        PhoneDisclosureCipher.EncryptedPhone encrypted = verifiedMobile == null
                ? null : phoneCipher.encrypt(userId, verifiedMobile);
        jdbc.update("""
                INSERT INTO user_application_authorization (
                  authorization_id, user_id, application_id, status, consent_version,
                  bound_phone_hash, bound_phone_masked, bound_phone_ciphertext,
                  bound_phone_nonce, bound_phone_key_id, phone_disclosure_version,
                  authorized_at, last_used_at, revoked_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), NULL, NULL, 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                  status = 'ACTIVE', consent_version = VALUES(consent_version),
                  bound_phone_hash = COALESCE(VALUES(bound_phone_hash), bound_phone_hash),
                  bound_phone_masked = COALESCE(VALUES(bound_phone_masked), bound_phone_masked),
                  bound_phone_ciphertext = COALESCE(VALUES(bound_phone_ciphertext), bound_phone_ciphertext),
                  bound_phone_nonce = COALESCE(VALUES(bound_phone_nonce), bound_phone_nonce),
                  bound_phone_key_id = COALESCE(VALUES(bound_phone_key_id), bound_phone_key_id),
                  phone_disclosure_version = IF(VALUES(bound_phone_ciphertext) IS NULL,
                    phone_disclosure_version,
                    GREATEST(phone_disclosure_version + 1, VALUES(phone_disclosure_version))),
                  authorized_at = UTC_TIMESTAMP(6), revoked_at = NULL,
                  version = version + 1, updated_at = UTC_TIMESTAMP(6)
                """, uuidToBytes(authorizationId), uuidToBytes(userId), applicationId, consentVersion,
                verifiedMobile == null ? null : phoneNumbers.hash(verifiedMobile),
                verifiedMobile == null ? null : phoneNumbers.mask(verifiedMobile),
                encrypted == null ? null : encrypted.ciphertext(),
                encrypted == null ? null : encrypted.nonce(),
                encrypted == null ? null : encrypted.keyId(),
                verifiedMobile == null ? 0 : phone.disclosureVersion() + 1);
        authorizationId = authorizationId(userId, applicationId);
        jdbc.update("DELETE FROM user_application_authorization_scope WHERE authorization_id = ?",
                uuidToBytes(authorizationId));
        for (String scope : normalized) {
            jdbc.update("""
                    INSERT INTO user_application_authorization_scope (
                      authorization_id, scope_code, granted_at
                    ) VALUES (?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(authorizationId), scope);
        }
        appendAuditAndEvent(authorizationId, userId, applicationId, "GRANTED", normalized,
                "identity.application-authorization.granted");
        recordOperation(userId, applicationId, "GRANT", idempotencyKey, requestHash);
        return get(userId, applicationId);
    }

    public PhoneChallengeView createPhoneChallenge(
            UUID userId, String applicationId, String mobile, String clientAddress) {
        if (!YSHOP_FOOD.equals(applicationId)) throw problem("APPLICATION_NOT_FOUND");
        get(userId, applicationId);
        try {
            ConsumerSmsChallengeService.ConsumerSmsChallenge challenge =
                    smsChallenges.create(mobile, clientAddress);
            Duration ttl = Duration.between(Instant.now(), challenge.expiresAt());
            redis.opsForValue().set(challengeBindingKey(challenge.challengeId()),
                    userId + "\n" + applicationId, ttl.isNegative() ? Duration.ofSeconds(1) : ttl);
            return new PhoneChallengeView(challenge.challengeId(), challenge.maskedMobile(),
                    challenge.expiresAt(), challenge.resendAfterSeconds());
        } catch (LoginRejectedException exception) {
            throw problem(exception.code());
        }
    }

    @Transactional
    public AuthorizationView grantScopes(
            UUID userId,
            String applicationId,
            Set<String> requestedScopes,
            int consentVersion,
            String idempotencyKey) {
        AuthorizationView current = get(userId, applicationId);
        if (!"ACTIVE".equals(current.state())) throw problem("APPLICATION_AUTHORIZATION_REQUIRED");
        Set<String> merged = new LinkedHashSet<>(current.grantedScopes());
        if (requestedScopes != null) merged.addAll(requestedScopes);
        return grant(userId, applicationId, merged, consentVersion, idempotencyKey);
    }

    @Transactional
    public void revoke(UUID userId, String applicationId, String idempotencyKey) {
        requireIdempotency(idempotencyKey);
        AuthorizationView current = get(userId, applicationId);
        byte[] requestHash = sha256(applicationId + "\nREVOKE");
        if (operationReplay(userId, applicationId, "REVOKE", idempotencyKey, requestHash)) return;
        if (current.authorizationId() == null || "REVOKED".equals(current.state())) {
            recordOperation(userId, applicationId, "REVOKE", idempotencyKey, requestHash);
            return;
        }
        jdbc.update("""
                UPDATE user_application_authorization
                   SET status = 'REVOKED', revoked_at = UTC_TIMESTAMP(6),
                       bound_phone_hash = NULL, bound_phone_masked = NULL,
                       bound_phone_ciphertext = NULL, bound_phone_nonce = NULL,
                       bound_phone_key_id = NULL,
                       version = version + 1, updated_at = UTC_TIMESTAMP(6)
                 WHERE authorization_id = ? AND user_id = ?
                """, uuidToBytes(current.authorizationId()), uuidToBytes(userId));
        appendAuditAndEvent(current.authorizationId(), userId, applicationId, "REVOKED",
                current.grantedScopes(), "identity.application-authorization.revoked");
        recordOperation(userId, applicationId, "REVOKE", idempotencyKey, requestHash);
    }

    @Transactional
    public DisclosureView disclosure(UUID userId, String applicationId) {
        AuthorizationView authorization = get(userId, applicationId);
        if (!"ACTIVE".equals(authorization.state())) {
            throw problem("APPLICATION_AUTHORIZATION_REQUIRED");
        }
        Set<String> scopes = authorization.grantedScopes();
        if (!scopes.contains("profile.basic") || !scopes.contains("profile.phone")) {
            throw problem("APPLICATION_SCOPES_INVALID");
        }
        PhoneRow phone = phone(userId);
        AuthorizationPhoneRow boundPhone = authorizationPhone(userId, applicationId);
        byte[] ciphertext = boundPhone == null ? phone.ciphertext() : boundPhone.ciphertext();
        byte[] nonce = boundPhone == null ? phone.nonce() : boundPhone.nonce();
        String keyId = boundPhone == null ? phone.keyId() : boundPhone.keyId();
        String mobile = phoneCipher.decrypt(
                userId, ciphertext, nonce, keyId);
        ProfileView profile = profiles.get(userId);
        jdbc.update("""
                UPDATE user_application_authorization
                   SET last_used_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                 WHERE authorization_id = ?
                """, uuidToBytes(authorization.authorizationId()));
        return new DisclosureView(
                authorization.authorizationId(), applicationId, userId, profile.nickname(),
                profile.avatarUrl(), mobile,
                boundPhone == null ? phone.disclosureVersion() : boundPhone.disclosureVersion(), scopes);
    }

    private AuthorizationView view(java.sql.ResultSet rs, UUID userId) throws java.sql.SQLException {
        byte[] authorizationBytes = rs.getBytes("authorization_id");
        UUID authorizationId = authorizationBytes == null ? null : bytesToUuid(authorizationBytes);
        Set<String> scopes = authorizationId == null ? Set.of() : scopes(authorizationId);
        String stored = rs.getString("status");
        String state = stored == null ? "NOT_AUTHORIZED" : stored;
        Integer authorizedConsent = (Integer) rs.getObject("authorized_consent_version");
        if (authorizationId != null && authorizedConsent != null
                && authorizedConsent != rs.getInt("consent_version")) {
            state = "NOT_AUTHORIZED";
            scopes = Set.of();
        }
        byte[] effectivePhone = YSHOP_FOOD.equals(rs.getString("application_id"))
                ? rs.getBytes("bound_phone_ciphertext") : rs.getBytes("phone_ciphertext");
        if (!"REVOKED".equals(state) && effectivePhone == null
                && rs.getBoolean("phone_required")) {
            state = "PHONE_UPGRADE_REQUIRED";
        }
        ProfileView profile = profiles.get(userId);
        return new AuthorizationView(
                authorizationId, rs.getString("application_id"), rs.getString("display_name"),
                rs.getString("developer_name"), rs.getString("icon_url"),
                rs.getString("privacy_policy_url"), rs.getString("terms_url"),
                rs.getInt("consent_version"), state, scopes, profile.nickname(),
                profile.avatarUrl(), YSHOP_FOOD.equals(rs.getString("application_id"))
                        ? rs.getString("bound_phone_masked") : rs.getString("phone_masked"),
                instant(rs, "authorized_at"), instant(rs, "last_used_at"), instant(rs, "revoked_at"));
    }

    private Set<String> scopes(UUID authorizationId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT scope_code FROM user_application_authorization_scope
                 WHERE authorization_id = ? ORDER BY scope_code
                """, String.class, uuidToBytes(authorizationId)));
    }

    private ApplicationDefinition definition(String applicationId) {
        List<ApplicationDefinition> rows = jdbc.query("""
                SELECT application_id, consent_version FROM external_application
                 WHERE application_id = ? AND status = 'ACTIVE'
                """, (rs, ignored) -> {
            Set<String> allowed = new LinkedHashSet<>();
            Set<String> required = new LinkedHashSet<>();
            jdbc.query("""
                    SELECT scope_code, required_scope FROM external_application_scope
                     WHERE application_id = ? ORDER BY scope_code
                    """, scopeRs -> {
                String code = scopeRs.getString("scope_code");
                allowed.add(code);
                if (scopeRs.getBoolean("required_scope")) required.add(code);
            }, applicationId);
            return new ApplicationDefinition(rs.getInt("consent_version"), allowed, required);
        }, applicationId);
        if (rows.isEmpty()) throw problem("APPLICATION_NOT_FOUND");
        return rows.get(0);
    }

    private PhoneRow phone(UUID userId) {
        List<PhoneRow> rows = jdbc.query("""
                SELECT phone_ciphertext, phone_nonce, phone_key_id, disclosure_version
                  FROM user_profile WHERE user_id = ? AND status = 'ACTIVE'
                """, (rs, ignored) -> new PhoneRow(
                rs.getBytes("phone_ciphertext"), rs.getBytes("phone_nonce"),
                rs.getString("phone_key_id"), rs.getLong("disclosure_version")), uuidToBytes(userId));
        if (rows.isEmpty()) throw problem("CONSUMER_NOT_FOUND");
        return rows.get(0);
    }

    private AuthorizationPhoneRow authorizationPhone(UUID userId, String applicationId) {
        List<AuthorizationPhoneRow> rows = jdbc.query("""
                SELECT bound_phone_ciphertext, bound_phone_nonce, bound_phone_key_id,
                       phone_disclosure_version
                  FROM user_application_authorization
                 WHERE user_id = ? AND application_id = ?
                   AND bound_phone_ciphertext IS NOT NULL
                """, (rs, ignored) -> new AuthorizationPhoneRow(
                rs.getBytes("bound_phone_ciphertext"), rs.getBytes("bound_phone_nonce"),
                rs.getString("bound_phone_key_id"), rs.getLong("phone_disclosure_version")),
                uuidToBytes(userId), applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID authorizationId(UUID userId, String applicationId) {
        List<UUID> rows = jdbc.query("""
                SELECT authorization_id FROM user_application_authorization
                 WHERE user_id = ? AND application_id = ?
                """, (rs, ignored) -> bytesToUuid(rs.getBytes(1)), uuidToBytes(userId), applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean operationReplay(
            UUID userId, String applicationId, String operation, String key, byte[] requestHash) {
        List<byte[]> rows = jdbc.query("""
                SELECT request_hash FROM application_authorization_operation
                 WHERE user_id = ? AND application_id = ? AND operation_type = ? AND idempotency_key = ?
                """, (rs, ignored) -> rs.getBytes(1), uuidToBytes(userId), applicationId, operation, key);
        if (rows.isEmpty()) return false;
        if (!MessageDigest.isEqual(rows.get(0), requestHash)) throw problem("IDEMPOTENCY_KEY_REUSED");
        return true;
    }

    private void recordOperation(
            UUID userId, String applicationId, String operation, String key, byte[] requestHash) {
        jdbc.update("""
                INSERT INTO application_authorization_operation (
                  operation_id, user_id, application_id, operation_type,
                  idempotency_key, request_hash, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, uuidToBytes(UuidV7.generate()), uuidToBytes(userId), applicationId,
                operation, key, requestHash);
    }

    private void appendAuditAndEvent(
            UUID authorizationId,
            UUID userId,
            String applicationId,
            String action,
            Set<String> scopes,
            String eventType) {
        UUID auditId = UuidV7.generate();
        UUID eventId = UuidV7.generate();
        String scopesJson = json(Map.of("scopes", scopes.stream().sorted().toList()));
        jdbc.update("""
                INSERT INTO application_authorization_audit (
                  audit_id, authorization_id, user_id, application_id,
                  action, scopes_json, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, uuidToBytes(auditId), uuidToBytes(authorizationId), uuidToBytes(userId),
                applicationId, action, scopesJson);
        jdbc.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
                ) VALUES (?, ?, 'application_authorization', ?, UTC_TIMESTAMP(6),
                          NULL, 1, ?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uuidToBytes(eventId), eventType, uuidToBytes(authorizationId),
                json(Map.of("userId", userId.toString(), "applicationId", applicationId,
                        "scopes", scopes.stream().sorted().toList())));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void requireIdempotency(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) {
            throw problem("IDEMPOTENCY_KEY_INVALID");
        }
    }

    private static String challengeBindingKey(String challengeId) {
        return "minipay:auth:application-phone:" + challengeId;
    }

    private static ApplicationAuthorizationException problem(String code) {
        return new ApplicationAuthorizationException(code);
    }

    public record AuthorizationView(
            UUID authorizationId, String applicationId, String displayName, String developerName,
            String iconUrl, String privacyPolicyUrl, String termsUrl, int consentVersion,
            String state, Set<String> grantedScopes, String nickname, String avatarUrl,
            String phoneMasked, Instant authorizedAt, Instant lastUsedAt, Instant revokedAt) { }
    public record DisclosureView(
            UUID authorizationId, String applicationId, UUID subject, String nickname,
            String avatarFetchUrl, String phone, long profileVersion, Set<String> grantedScopes) { }
    private record ApplicationDefinition(int consentVersion, Set<String> allowedScopes,
                                         Set<String> requiredScopes) { }
    private record PhoneRow(byte[] ciphertext, byte[] nonce, String keyId, long disclosureVersion) { }
    private record AuthorizationPhoneRow(byte[] ciphertext, byte[] nonce, String keyId,
                                         long disclosureVersion) { }
    public record PhoneChallengeView(String challengeId, String maskedMobile, Instant expiresAt,
                                     long resendAfterSeconds) { }
}
