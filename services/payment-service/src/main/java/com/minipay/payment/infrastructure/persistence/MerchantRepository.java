package com.minipay.payment.infrastructure.persistence;

import com.minipay.payment.application.service.UuidV7;
import com.minipay.payment.infrastructure.security.MerchantSecretCipher;
import com.minipay.payment.domain.model.MerchantStatus;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRepository {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final MerchantSecretCipher codeCipher;

    public MerchantRepository(JdbcTemplate jdbc, MerchantSecretCipher codeCipher) {
        this.jdbc = jdbc;
        this.codeCipher = codeCipher;
    }

    public Optional<MerchantRow> findByOwner(UUID ownerUserId) {
        List<MerchantRow> rows = jdbc.query("""
                SELECT merchant_id, merchant_no, owner_user_id, name, short_name,
                       COALESCE(mcc_code, 'OTHER') AS category, contact_name,
                       contact_mobile, contact_email, address, latitude, longitude, shop_images,
                       source, profile_confirmed_at, NULL AS onboarding_id,
                       NULL AS agreement_version, NULL AS agreed_at, status,
                       (status <> 'ACTIVE') AS receive_locked, remark,
                       default_application_id, version, created_at, updated_at
                FROM merchant WHERE owner_user_id = ?
                ORDER BY created_at, merchant_id
                """, (rs, ignored) -> mapMerchant(rs), uuid(ownerUserId));
        return rows.stream().findFirst();
    }

    public List<MerchantRow> findAllByOwner(UUID ownerUserId) {
        return jdbc.query("""
                SELECT merchant_id, merchant_no, owner_user_id, name, short_name,
                       COALESCE(mcc_code, 'OTHER') AS category, contact_name,
                       contact_mobile, contact_email, address, latitude, longitude, shop_images,
                       source, profile_confirmed_at, NULL AS onboarding_id,
                       NULL AS agreement_version, NULL AS agreed_at, status,
                       (status <> 'ACTIVE') AS receive_locked, remark,
                       default_application_id, version, created_at, updated_at
                FROM merchant WHERE owner_user_id = ?
                ORDER BY created_at, merchant_id
                """, (rs, ignored) -> mapMerchant(rs), uuid(ownerUserId));
    }

    public Optional<MerchantRow> findOwned(UUID ownerUserId, UUID merchantId) {
        List<MerchantRow> rows = jdbc.query("""
                SELECT merchant_id, merchant_no, owner_user_id, name, short_name,
                       COALESCE(mcc_code, 'OTHER') AS category, contact_name,
                       contact_mobile, contact_email, address, latitude, longitude, shop_images,
                       source, profile_confirmed_at, NULL AS onboarding_id,
                       NULL AS agreement_version, NULL AS agreed_at, status,
                       (status <> 'ACTIVE') AS receive_locked, remark,
                       default_application_id, version, created_at, updated_at
                FROM merchant WHERE owner_user_id = ? AND merchant_id = ?
                """, (rs, ignored) -> mapMerchant(rs), uuid(ownerUserId), uuid(merchantId));
        return rows.stream().findFirst();
    }

    /** Must only be used inside a transaction that creates merchant-owned defaults. */
    public Optional<MerchantRow> findOwnedForUpdate(UUID ownerUserId, UUID merchantId) {
        List<MerchantRow> rows = jdbc.query("""
                SELECT merchant_id, merchant_no, owner_user_id, name, short_name,
                       COALESCE(mcc_code, 'OTHER') AS category, contact_name,
                       contact_mobile, contact_email, address, latitude, longitude, shop_images,
                       source, profile_confirmed_at, NULL AS onboarding_id,
                       NULL AS agreement_version, NULL AS agreed_at, status,
                       (status <> 'ACTIVE') AS receive_locked, remark,
                       default_application_id, version, created_at, updated_at
                FROM merchant WHERE owner_user_id = ? AND merchant_id = ? FOR UPDATE
                """, (rs, ignored) -> mapMerchant(rs), uuid(ownerUserId), uuid(merchantId));
        return rows.stream().findFirst();
    }

    public boolean updateOwnerProfile(MerchantRow merchant, String shortName, String category, String contactName,
            String contactMobile, String contactEmail, String address, BigDecimal latitude, BigDecimal longitude,
            String shopImages, String remark, long version) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE merchant
                SET short_name = ?, mcc_code = ?, contact_name = ?, contact_mobile = ?, contact_email = ?,
                    address = ?, latitude = ?, longitude = ?, shop_images = ?, remark = ?,
                    profile_confirmed_at = COALESCE(profile_confirmed_at, ?),
                    version = version + 1, updated_at = ?
                WHERE merchant_id = ? AND status = 'ACTIVE' AND version = ?
                """, shortName, category, contactName, contactMobile, contactEmail, address,
                latitude, longitude, shopImages, remark, now, now, uuid(merchant.merchantId()), version);
        return changed == 1;
    }

    public Optional<MerchantApplicationRow> findDefaultApplication(UUID merchantId) {
        List<MerchantApplicationRow> rows = jdbc.query("""
                SELECT a.application_id, a.merchant_id, a.app_id, a.name AS app_name, a.app_type, a.is_default,
                       a.notify_url, a.refund_notify_url, a.ip_white_list, a.api_permissions,
                       a.available_channels, a.status,
                       EXISTS(SELECT 1 FROM payment_order p WHERE p.application_id = a.application_id) AS has_transactions,
                       a.version, a.created_at, a.updated_at
                FROM merchant_application a JOIN merchant m ON m.default_application_id = a.application_id
                WHERE m.merchant_id = ?
                """, (rs, ignored) -> mapApplication(rs), uuid(merchantId));
        return rows.stream().findFirst();
    }

    public List<MerchantApplicationRow> findApplications(UUID merchantId) {
        return jdbc.query("""
                SELECT application_id, merchant_id, app_id, name AS app_name, app_type, is_default,
                       notify_url, refund_notify_url, ip_white_list, api_permissions,
                       available_channels, status,
                       EXISTS(SELECT 1 FROM payment_order p WHERE p.application_id = merchant_application.application_id) AS has_transactions,
                       version, created_at, updated_at
                FROM merchant_application WHERE merchant_id = ? ORDER BY is_default DESC, created_at DESC
                """, (rs, ignored) -> mapApplication(rs), uuid(merchantId));
    }

    public Optional<MerchantApplicationRow> findOwnedApplication(UUID merchantId, String appId) {
        List<MerchantApplicationRow> rows = jdbc.query("""
                SELECT application_id, merchant_id, app_id, name AS app_name, app_type, is_default,
                       notify_url, refund_notify_url, ip_white_list, api_permissions,
                       available_channels, status,
                       EXISTS(SELECT 1 FROM payment_order p WHERE p.application_id = merchant_application.application_id) AS has_transactions,
                       version, created_at, updated_at
                FROM merchant_application WHERE merchant_id = ? AND app_id = ?
                """, (rs, ignored) -> mapApplication(rs), uuid(merchantId), appId);
        return rows.stream().findFirst();
    }

    public MerchantApplicationRow createApplication(UUID merchantId, String appName, String notifyUrl,
            String refundNotifyUrl, String ipWhiteList, String permissions, String channels, byte[] secretCiphertext) {
        UUID applicationId = UuidV7.generate();
        Instant now = Instant.now();
        String appId = "mp_app_" + applicationId.toString().replace("-", "");
        int inserted = jdbc.update("""
                INSERT INTO merchant_application (application_id, merchant_id, app_id, app_secret_ciphertext,
                                                  name, app_type, is_default, notify_url, refund_notify_url,
                                                  ip_white_list, api_permissions, available_channels, status,
                                                  configured_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SELF_USE', FALSE, ?, ?, ?, ?, ?, 'DISABLED', ?, 0, ?, ?)
                """, uuid(applicationId), uuid(merchantId), appId, secretCiphertext, appName, notifyUrl,
                refundNotifyUrl, ipWhiteList, permissions, channels, now, now, now);
        if (inserted != 1) throw new IllegalStateException("MERCHANT_APPLICATION_CREATE_FAILED");
        createCollectionCode(applicationId);
        return findOwnedApplication(merchantId, appId).orElseThrow();
    }

    public boolean updateApplication(MerchantApplicationRow application, String appName, String notifyUrl,
            String refundNotifyUrl, String ipWhiteList, String permissions, String channels, long version) {
        int changed = jdbc.update("""
                UPDATE merchant_application
                SET name = ?, notify_url = ?, refund_notify_url = ?, ip_white_list = ?,
                    api_permissions = ?, available_channels = ?, configured_at = ?,
                    version = version + 1, updated_at = ?
                WHERE application_id = ? AND merchant_id = ? AND version = ?
                """, appName, notifyUrl, refundNotifyUrl, ipWhiteList, permissions, channels, Instant.now(), Instant.now(),
                uuid(application.applicationId()), uuid(application.merchantId()), version);
        return changed == 1;
    }

    public boolean updateApplicationStatus(MerchantApplicationRow application, String status, long version) {
        int changed = jdbc.update("""
                UPDATE merchant_application SET status = ?, version = version + 1, updated_at = ?
                WHERE application_id = ? AND merchant_id = ? AND version = ?
                """, status, Instant.now(), uuid(application.applicationId()), uuid(application.merchantId()), version);
        if (changed == 1 && "DISABLED".equals(status)) {
            int codeChanged = jdbc.update("""
                    UPDATE merchant_collection_code SET status = 'DISABLED', version = version + 1, updated_at = ?
                    WHERE application_id = ? AND status <> 'DISABLED'
                    """, Instant.now(), uuid(application.applicationId()));
        }
        return changed == 1;
    }

    public boolean rotateApplicationSecret(MerchantApplicationRow application, byte[] secretCiphertext, long version) {
        int changed = jdbc.update("""
                UPDATE merchant_application
                SET app_secret_ciphertext = ?, secret_key_version = secret_key_version + 1,
                    secret_first_viewed_at = NULL, secret_rotated_at = ?,
                    version = version + 1, updated_at = ?
                WHERE application_id = ? AND merchant_id = ? AND version = ?
                """, secretCiphertext, Instant.now(), Instant.now(), uuid(application.applicationId()),
                uuid(application.merchantId()), version);
        return changed == 1;
    }

    public Optional<String> claimApplicationSecret(UUID merchantId, UUID applicationId) {
        int changed = jdbc.update("""
                UPDATE merchant_application
                   SET secret_first_viewed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                 WHERE merchant_id = ? AND application_id = ?
                   AND app_secret_ciphertext IS NOT NULL
                   AND secret_first_viewed_at IS NULL
                """, uuid(merchantId), uuid(applicationId));
        if (changed != 1) {
            return Optional.empty();
        }
        byte[] ciphertext = jdbc.queryForObject("""
                SELECT app_secret_ciphertext FROM merchant_application
                 WHERE merchant_id = ? AND application_id = ?
                """, byte[].class, uuid(merchantId), uuid(applicationId));
        return Optional.of(codeCipher.decrypt(ciphertext));
    }

    public boolean deleteApplication(MerchantApplicationRow application, long version) {
        if (application.defaultApplication() || application.hasTransactions()) return false;
        jdbc.update("DELETE FROM merchant_collection_code WHERE application_id = ?", uuid(application.applicationId()));
        int changed = jdbc.update("""
                DELETE FROM merchant_application
                WHERE application_id = ? AND merchant_id = ? AND is_default = FALSE AND version = ?
                """, uuid(application.applicationId()), uuid(application.merchantId()), version);
        return changed == 1;
    }

    public CreatedDefaultApplication createDefaultApplication(UUID merchantId, byte[] secretCiphertext) {
        UUID applicationId = UuidV7.generate();
        Instant now = Instant.now();
        String appId = "mp_app_" + applicationId.toString().replace("-", "");
        jdbc.update("""
                INSERT INTO merchant_application (application_id, merchant_id, app_id, app_secret_ciphertext,
                                                  name, app_type, is_default, notify_url, refund_notify_url,
                                                  ip_white_list, api_permissions, available_channels, status,
                                                  configured_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, '默认应用', 'SELF_USE', TRUE, NULL, NULL, NULL,
                        'PAYMENT_CREATE,PAYMENT_QUERY,REFUND_CREATE', 'WALLET,ALIPAY,WECHAT', 'ACTIVE', ?, 0, ?, ?)
                """, uuid(applicationId), uuid(merchantId), appId, secretCiphertext, now, now, now);
        int changed = jdbc.update("""
                UPDATE merchant SET default_application_id = ?, initialized_at = ?,
                                    version = version + 1, updated_at = ?
                WHERE merchant_id = ? AND default_application_id IS NULL
                """, uuid(applicationId), now, now, uuid(merchantId));
        if (changed != 1) {
            throw new IllegalStateException("MERCHANT_INITIALIZATION_LOCK_LOST");
        }
        CollectionCodeRow collectionCode = createCollectionCode(applicationId);
        return new CreatedDefaultApplication(findDefaultApplication(merchantId).orElseThrow(), collectionCode);
    }

    public CollectionCodeRow createCollectionCode(UUID applicationId) {
        return createCollectionCode(applicationId, "ENABLED");
    }

    public CollectionCodeRow ensureCollectionCode(UUID applicationId, boolean enabled) {
        CollectionCodeRow existing = findCollectionCode(applicationId).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return createCollectionCode(applicationId, enabled ? "ENABLED" : "DISABLED");
        } catch (DuplicateKeyException race) {
            return findCollectionCode(applicationId).orElseThrow(() -> race);
        }
    }

    private CollectionCodeRow createCollectionCode(UUID applicationId, String status) {
        UUID codeId = UuidV7.generate();
        Instant now = Instant.now();
        String token = randomToken(codeId);
        jdbc.update("""
                INSERT INTO merchant_collection_code (code_id, application_id, token_hash, token_ciphertext, status, key_version,
                                                       version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?)
                """, uuid(codeId), uuid(applicationId), hash(token), codeCipher.encrypt(token), status, now, now);
        return new CollectionCodeRow(codeId, applicationId, token, status, 1, 0);
    }

    public void provisionApprovedApplication(UUID merchantId, UUID applicationId, byte[] secretCiphertext) {
        int updated = jdbc.update("""
                UPDATE merchant_application
                   SET app_type = 'THIRD_PARTY', app_secret_ciphertext = ?,
                       secret_key_version = 1, secret_first_viewed_at = NULL,
                       secret_rotated_at = UTC_TIMESTAMP(6),
                       api_permissions = 'PAYMENT_CREATE,PAYMENT_QUERY,REFUND_CREATE,BILL_QUERY',
                       available_channels = 'WALLET,ALIPAY,WECHAT',
                       version = version + 1, updated_at = UTC_TIMESTAMP(6)
                 WHERE merchant_id = ? AND application_id = ?
                   AND app_secret_ciphertext IS NULL
                """, secretCiphertext, uuid(merchantId), uuid(applicationId));
        if (updated != 1) {
            throw new IllegalStateException("APPLICATION_PROVISIONING_CONFLICT");
        }
        createCollectionCode(applicationId);
    }

    public Optional<CollectionCodeRow> findDefaultCode(UUID merchantId) {
        List<CollectionCodeRow> rows = jdbc.query("""
                SELECT c.code_id, c.application_id, c.token_ciphertext, c.status, c.key_version, c.version
                FROM merchant_collection_code c
                JOIN merchant m ON m.default_application_id = c.application_id
                WHERE m.merchant_id = ?
                """, (rs, ignored) -> new CollectionCodeRow(fromBytes(rs.getBytes("code_id")),
                fromBytes(rs.getBytes("application_id")), decryptToken(rs.getBytes("token_ciphertext")), rs.getString("status"),
                rs.getLong("key_version"), rs.getLong("version")), uuid(merchantId));
        return rows.stream().findFirst();
    }

    public Optional<CollectionCodeRow> findCollectionCode(UUID applicationId) {
        List<CollectionCodeRow> rows = jdbc.query("""
                SELECT code_id, application_id, token_ciphertext, status, key_version, version
                FROM merchant_collection_code WHERE application_id = ?
                """, (rs, ignored) -> new CollectionCodeRow(fromBytes(rs.getBytes("code_id")),
                fromBytes(rs.getBytes("application_id")), decryptToken(rs.getBytes("token_ciphertext")), rs.getString("status"),
                rs.getLong("key_version"), rs.getLong("version")), uuid(applicationId));
        return rows.stream().findFirst();
    }

    public Optional<CollectionCodeRow> replaceCollectionCode(UUID applicationId, long version) {
        CollectionCodeRow current = findCollectionCode(applicationId).orElse(null);
        if (current == null || current.version() != version) return Optional.empty();
        String token = randomToken(current.codeId());
        int changed = jdbc.update("""
                UPDATE merchant_collection_code
                SET token_hash = ?, token_ciphertext = ?, status = 'ENABLED', key_version = key_version + 1,
                    version = version + 1, updated_at = ?
                WHERE code_id = ? AND application_id = ? AND version = ?
                """, hash(token), codeCipher.encrypt(token), Instant.now(), uuid(current.codeId()), uuid(applicationId), version);
        return changed == 1 ? Optional.of(new CollectionCodeRow(current.codeId(), applicationId, token,
                "ENABLED", current.keyVersion() + 1, current.version() + 1)) : Optional.empty();
    }

    public boolean updateCollectionCodeStatus(UUID applicationId, String status, long version) {
        int changed = jdbc.update("""
                UPDATE merchant_collection_code SET status = ?, version = version + 1, updated_at = ?
                WHERE application_id = ? AND version = ?
                """, status, Instant.now(), uuid(applicationId), version);
        return changed == 1;
    }

    private String decryptToken(byte[] ciphertext) {
        return ciphertext == null ? null : codeCipher.decrypt(ciphertext);
    }

    public Optional<ResolutionSourceRow> findResolutionSource(byte[] tokenHash) {
        List<ResolutionSourceRow> rows = jdbc.query("""
                SELECT m.merchant_id, m.merchant_no, m.name AS merchant_name, m.status AS merchant_status,
                       (m.status <> 'ACTIVE') AS receive_locked, a.application_id, a.app_id, a.status AS application_status,
                       a.available_channels, c.code_id, c.status AS code_status
                FROM merchant_collection_code c
                JOIN merchant_application a ON a.application_id = c.application_id
                JOIN merchant m ON m.merchant_id = a.merchant_id
                WHERE c.token_hash = ?
                """, (rs, ignored) -> new ResolutionSourceRow(
                fromBytes(rs.getBytes("merchant_id")), rs.getString("merchant_no"),
                rs.getString("merchant_name"), rs.getString("merchant_status"), rs.getBoolean("receive_locked"),
                fromBytes(rs.getBytes("application_id")), rs.getString("app_id"),
                rs.getString("application_status"), rs.getString("available_channels"),
                fromBytes(rs.getBytes("code_id")), rs.getString("code_status")), tokenHash);
        return rows.stream().findFirst();
    }

    public ResolutionRow createResolution(UUID merchantId, UUID applicationId, Instant expiresAt) {
        UUID resolutionId = UuidV7.generate();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO merchant_scan_resolution (resolution_id, application_id, merchant_id, expires_at,
                                                      consumed_at, version, created_at)
                VALUES (?, ?, ?, ?, NULL, 0, ?)
                """, uuid(resolutionId), uuid(applicationId), uuid(merchantId), expiresAt, now);
        return new ResolutionRow(resolutionId, applicationId, merchantId, expiresAt, null, 0);
    }

    public Optional<ResolutionRow> consumeResolution(UUID resolutionId, String merchantNo, String appId) {
        Optional<ResolutionRow> row = findResolution(resolutionId);
        if (row.isEmpty() || row.get().consumedAt() != null || row.get().expiresAt().isBefore(Instant.now())) return Optional.empty();
        ResolutionRow current = row.get();
        int changed = jdbc.update("""
                UPDATE merchant_scan_resolution r
                JOIN merchant m ON m.merchant_id = r.merchant_id
                JOIN merchant_application a ON a.application_id = r.application_id
                SET r.consumed_at = ?, r.version = r.version + 1
                WHERE r.resolution_id = ? AND r.consumed_at IS NULL AND r.expires_at > ?
                      AND m.merchant_no = ? AND a.app_id = ?
                """, Instant.now(), uuid(resolutionId), Instant.now(), merchantNo, appId);
        return changed == 1 ? Optional.of(current) : Optional.empty();
    }

    public Optional<ResolutionRow> findResolution(UUID resolutionId) {
        List<ResolutionRow> rows = jdbc.query("""
                SELECT resolution_id, application_id, merchant_id, expires_at, consumed_at, version
                FROM merchant_scan_resolution WHERE resolution_id = ?
                """, (rs, ignored) -> new ResolutionRow(fromBytes(rs.getBytes("resolution_id")),
                fromBytes(rs.getBytes("application_id")), fromBytes(rs.getBytes("merchant_id")),
                instant(rs, "expires_at"), instant(rs, "consumed_at"), rs.getLong("version")), uuid(resolutionId));
        return rows.stream().findFirst();
    }

    public Optional<PaymentResolutionRow> lockPaymentResolution(UUID resolutionId) {
        List<PaymentResolutionRow> rows = jdbc.query("""
                SELECT r.resolution_id, r.merchant_id, r.application_id, r.expires_at,
                       r.consumed_at, m.merchant_no, m.name AS merchant_name,
                       m.owner_user_id, m.status AS merchant_status, a.app_id,
                       a.status AS application_status, a.available_channels
                  FROM merchant_scan_resolution r
                  JOIN merchant m ON m.merchant_id = r.merchant_id
                  JOIN merchant_application a ON a.application_id = r.application_id
                 WHERE r.resolution_id = ? FOR UPDATE
                """, (rs, ignored) -> new PaymentResolutionRow(
                fromBytes(rs.getBytes("resolution_id")), fromBytes(rs.getBytes("merchant_id")),
                fromBytes(rs.getBytes("application_id")), rs.getString("merchant_no"),
                rs.getString("merchant_name"), fromBytes(rs.getBytes("owner_user_id")),
                rs.getString("merchant_status"), rs.getString("app_id"),
                rs.getString("application_status"), rs.getString("available_channels"),
                instant(rs, "expires_at"), instant(rs, "consumed_at")), uuid(resolutionId));
        return rows.stream().findFirst();
    }

    /** Resolve an active merchant application without relying on a one-time scan resolution. */
    public Optional<MerchantSettlementContextRow> findMerchantSettlementContext(
            UUID merchantId, UUID applicationId) {
        List<MerchantSettlementContextRow> rows = jdbc.query("""
                SELECT m.merchant_id, a.application_id, m.merchant_no,
                       m.name AS merchant_name, m.owner_user_id,
                       m.status AS merchant_status, a.app_id,
                       a.status AS application_status, a.available_channels
                  FROM merchant m
                  JOIN merchant_application a ON a.merchant_id = m.merchant_id
                 WHERE m.merchant_id = ? AND a.application_id = ?
                """, (rs, ignored) -> new MerchantSettlementContextRow(
                fromBytes(rs.getBytes("merchant_id")),
                fromBytes(rs.getBytes("application_id")),
                rs.getString("merchant_no"),
                rs.getString("merchant_name"),
                fromBytes(rs.getBytes("owner_user_id")),
                rs.getString("merchant_status"),
                rs.getString("app_id"),
                rs.getString("application_status"),
                rs.getString("available_channels")),
                uuid(merchantId), uuid(applicationId));
        return rows.stream().findFirst();
    }

    /** Resolve the configured food-platform application without accepting merchant data from events. */
    public Optional<MerchantSettlementContextRow> findMerchantSettlementContextByAppId(String appId) {
        List<MerchantSettlementContextRow> rows = jdbc.query("""
                SELECT m.merchant_id, a.application_id, m.merchant_no,
                       m.name AS merchant_name, m.owner_user_id,
                       m.status AS merchant_status, a.app_id,
                       a.status AS application_status, a.available_channels
                  FROM merchant_application a
                  JOIN merchant m ON m.merchant_id = a.merchant_id
                 WHERE a.app_id = ?
                """, (rs, ignored) -> new MerchantSettlementContextRow(
                fromBytes(rs.getBytes("merchant_id")),
                fromBytes(rs.getBytes("application_id")),
                rs.getString("merchant_no"),
                rs.getString("merchant_name"),
                fromBytes(rs.getBytes("owner_user_id")),
                rs.getString("merchant_status"),
                rs.getString("app_id"),
                rs.getString("application_status"),
                rs.getString("available_channels")), appId);
        return rows.stream().findFirst();
    }

    public boolean consumePaymentResolution(UUID resolutionId, UUID paymentOrderId) {
        return jdbc.update("""
                UPDATE merchant_scan_resolution
                   SET consumed_at = UTC_TIMESTAMP(6), consumed_by_order_id = ?,
                       version = version + 1
                 WHERE resolution_id = ? AND consumed_at IS NULL
                   AND expires_at > UTC_TIMESTAMP(6)
                """, uuid(paymentOrderId), uuid(resolutionId)) == 1;
    }

    public List<DashboardRow> dashboard(UUID merchantId, LocalDate start, LocalDate end) {
        return jdbc.query("""
                SELECT metric_date AS stat_date, payment_amount_cent,
                       successful_payment_count AS payment_count, refund_amount_cent,
                       successful_refund_count AS refund_count, calculated_at AS updated_at
                FROM merchant_daily_metric
                WHERE merchant_id = ? AND metric_date BETWEEN ? AND ? ORDER BY metric_date
                """, (rs, ignored) -> new DashboardRow(rs.getObject("stat_date", LocalDate.class),
                rs.getLong("payment_amount_cent"), rs.getLong("payment_count"),
                rs.getLong("refund_amount_cent"), rs.getLong("refund_count"), instant(rs, "updated_at")),
                uuid(merchantId), start, end);
    }

    public DashboardSummaryRow dashboardSummary(UUID merchantId, LocalDate start, LocalDate end) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(payment_amount_cent), 0) AS payment_amount_cent,
                       COALESCE(SUM(successful_payment_count), 0) AS payment_count,
                       COALESCE(SUM(refund_amount_cent), 0) AS refund_amount_cent,
                       COALESCE(SUM(successful_refund_count), 0) AS refund_count
                FROM merchant_daily_metric WHERE merchant_id = ? AND metric_date BETWEEN ? AND ?
                """, (rs, ignored) -> new DashboardSummaryRow(rs.getLong("payment_amount_cent"),
                rs.getLong("payment_count"), rs.getLong("refund_amount_cent"), rs.getLong("refund_count")),
                uuid(merchantId), start, end);
    }

    /** Read-only merchant projection of payment orders; no order-state mutation belongs here. */
    public List<MerchantOrderRow> orders(UUID merchantId, String orderNo, String status, String channel,
            Instant from, Instant to, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = orderWhere(merchantId, orderNo, status, channel, from, to, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                SELECT p.pay_order_no, p.merchant_order_no, p.app_id, p.amount_cent, p.currency, p.subject,
                       p.channel, COALESCE(a.available_channels, p.channel) AS allowed_channels,
                       p.status, p.expires_at, p.created_at, p.updated_at,
                       r.refund_order_no, r.amount_cent AS refund_amount_cent,
                       r.status AS refund_status, r.reason AS refund_reason
                FROM payment_order p
                LEFT JOIN merchant_application a ON a.application_id = p.application_id
                LEFT JOIN refund_order r ON r.pay_order_id = p.pay_order_id
                """ + where + " ORDER BY p.created_at DESC LIMIT ? OFFSET ?",
                (rs, ignored) -> new MerchantOrderRow(rs.getString("pay_order_no"),
                rs.getString("merchant_order_no"), rs.getString("app_id"), rs.getLong("amount_cent"),
                rs.getString("currency"), rs.getString("subject"), rs.getString("channel"),
                rs.getString("allowed_channels"), rs.getString("status"), instant(rs, "expires_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("refund_order_no"),
                (Long) rs.getObject("refund_amount_cent"), rs.getString("refund_status"),
                rs.getString("refund_reason")), args.toArray());
    }

    public long countOrders(UUID merchantId, String orderNo, String status, String channel,
            Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        String where = orderWhere(merchantId, orderNo, status, channel, from, to, args);
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM payment_order p " + where,
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String orderWhere(UUID merchantId, String orderNo, String status, String channel,
            Instant from, Instant to, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE p.merchant_id = ?");
        args.add(uuid(merchantId));
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" AND (p.pay_order_no = ? OR p.merchant_order_no = ?)");
            args.add(orderNo.trim());
            args.add(orderNo.trim());
        }
        if (status != null && !status.isBlank()) {
            if ("REFUNDED".equalsIgnoreCase(status.trim())) {
                where.append(" AND EXISTS (SELECT 1 FROM refund_order rf WHERE rf.pay_order_id=p.pay_order_id AND rf.status='SUCCEEDED')");
            } else {
                where.append(" AND p.status = ?");
                args.add(status.trim());
            }
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND p.channel = ?");
            args.add(channel.trim());
        }
        if (from != null) {
            where.append(" AND p.created_at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND p.created_at < ?");
            args.add(java.sql.Timestamp.from(to));
        }
        return where.toString();
    }

    /** Owner-bound lookup used by the merchant order-detail/refund façade. */
    public Optional<MerchantOrderDetailRow> findOrder(UUID merchantId, String paymentOrderNo) {
        List<MerchantOrderDetailRow> rows = jdbc.query("""
                SELECT p.pay_order_id, p.pay_order_no, p.merchant_order_no, p.app_id, p.amount_cent, p.currency, p.subject,
                       p.channel, COALESCE(a.available_channels, p.channel) AS allowed_channels,
                       p.status, p.expires_at, p.created_at, p.updated_at,
                       r.refund_order_no, r.amount_cent AS refund_amount_cent,
                       r.status AS refund_status, r.reason AS refund_reason
                FROM payment_order p
                LEFT JOIN merchant_application a ON a.application_id = p.application_id
                LEFT JOIN refund_order r ON r.pay_order_id = p.pay_order_id
                WHERE p.merchant_id = ? AND p.pay_order_no = ? LIMIT 1
                """, (rs, ignored) -> new MerchantOrderDetailRow(fromBytes(rs.getBytes("pay_order_id")),
                rs.getString("pay_order_no"), rs.getString("merchant_order_no"), rs.getString("app_id"),
                rs.getLong("amount_cent"), rs.getString("currency"), rs.getString("subject"),
                rs.getString("channel"), rs.getString("allowed_channels"), rs.getString("status"),
                instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("refund_order_no"), (Long) rs.getObject("refund_amount_cent"),
                rs.getString("refund_status"), rs.getString("refund_reason")), uuid(merchantId), paymentOrderNo);
        return rows.stream().findFirst();
    }

    /** Channel aggregation is an order-table read model; it contains no merchant-owned state. */
    public List<ChannelDistributionRow> channelDistribution(UUID merchantId) {
        return jdbc.query("""
                SELECT COALESCE(NULLIF(app_id, ''), 'UNKNOWN') AS app_id,
                       COALESCE(NULLIF(channel, ''), 'UNKNOWN') AS channel, COUNT(1) AS order_count,
                       COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN amount_cent ELSE 0 END), 0) AS amount_cent
                FROM payment_order WHERE merchant_id = ?
                GROUP BY COALESCE(NULLIF(app_id, ''), 'UNKNOWN'), COALESCE(NULLIF(channel, ''), 'UNKNOWN')
                ORDER BY amount_cent DESC, order_count DESC
                """, (rs, ignored) -> new ChannelDistributionRow(rs.getString("app_id"), rs.getString("channel"),
                rs.getLong("order_count"), rs.getLong("amount_cent")), uuid(merchantId));
    }

    public long countPendingOnboardings() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM merchant_apply WHERE apply_status = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    public long countNotificationsAwaitingManualRetry() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM merchant_notification WHERE status = 'FAILED'", Long.class);
        return count == null ? 0 : count;
    }

    public void audit(UUID merchantId, String actorId, String actorType, String operation, String resourceType,
            String resourceId, String idempotencyKey, String requestId, String before, String after) {
        jdbc.update("""
                INSERT INTO merchant_operation_audit (audit_id, merchant_id, actor_id, actor_type, operation,
                                                      resource_type, resource_id, idempotency_key, request_id,
                                                      before_summary, after_summary, result, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?)
                """, uuid(UuidV7.generate()), merchantId == null ? null : uuid(merchantId), actorId, actorType,
                operation, resourceType, resourceId, idempotencyKey, requestId, before, after, Instant.now());
    }

    private static MerchantRow mapMerchant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MerchantRow(fromBytes(rs.getBytes("merchant_id")), rs.getString("merchant_no"),
                fromBytes(rs.getBytes("owner_user_id")), rs.getString("name"), rs.getString("short_name"),
                rs.getString("category"), rs.getString("contact_name"), rs.getString("contact_mobile"),
                rs.getString("contact_email"), rs.getString("address"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("shop_images"),
                rs.getString("source"), instant(rs, "profile_confirmed_at"),
                fromBytesNullable(rs.getBytes("onboarding_id")),
                rs.getString("agreement_version"), instant(rs, "agreed_at"),
                MerchantStatus.valueOf(rs.getString("status")), rs.getBoolean("receive_locked"),
                rs.getString("remark"), fromBytesNullable(rs.getBytes("default_application_id")),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static MerchantApplicationRow mapApplication(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MerchantApplicationRow(fromBytes(rs.getBytes("application_id")),
                fromBytes(rs.getBytes("merchant_id")), rs.getString("app_id"), rs.getString("app_name"),
                rs.getString("app_type"), rs.getBoolean("is_default"), rs.getString("notify_url"),
                rs.getString("refund_notify_url"), rs.getString("ip_white_list"), rs.getString("api_permissions"),
                rs.getString("available_channels"), rs.getString("status"), rs.getBoolean("has_transactions"),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static byte[] uuid(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static UUID fromBytes(byte[] value) {
        if (value == null || value.length != 16) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static UUID fromBytesNullable(byte[] value) { return value == null ? null : fromBytes(value); }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException { java.sql.Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    public static byte[] hash(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static String randomToken(UUID value) {
        byte[] entropy = new byte[24];
        TOKEN_RANDOM.nextBytes(entropy);
        return "mc_" + value.toString().replace("-", "")
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    public record MerchantRow(UUID merchantId, String merchantNo, UUID ownerUserId, String name, String shortName,
                              String category, String contactName, String contactMobile, String contactEmail,
                              String address, BigDecimal latitude, BigDecimal longitude, String shopImages,
                              String source, Instant profileConfirmedAt, UUID onboardingId,
                              String agreementVersion, Instant agreedAt,
                              MerchantStatus status, boolean receiveLocked, String remark,
                              UUID defaultApplicationId, long version, Instant createdAt, Instant updatedAt) { }
    public record MerchantApplicationRow(UUID applicationId, UUID merchantId, String appId, String appName,
                                         String appType, boolean defaultApplication, String notifyUrl,
                                         String refundNotifyUrl, String ipWhiteList, String apiPermissions,
                                         String availableChannels, String status, boolean hasTransactions, long version,
                                         Instant createdAt, Instant updatedAt) { }
    public record CollectionCodeRow(UUID codeId, UUID applicationId, String token, String status, long keyVersion, long version) { }
    public record CreatedDefaultApplication(MerchantApplicationRow application, CollectionCodeRow collectionCode) { }
    public record ResolutionSourceRow(UUID merchantId, String merchantNo, String merchantName, String merchantStatus,
                                      boolean receiveLocked, UUID applicationId, String appId, String applicationStatus,
                                      String availableChannels, UUID codeId, String codeStatus) { }
    public record ResolutionRow(UUID resolutionId, UUID applicationId, UUID merchantId, Instant expiresAt, Instant consumedAt, long version) { }
    public record PaymentResolutionRow(
            UUID resolutionId,
            UUID merchantId,
            UUID applicationId,
            String merchantNo,
            String merchantName,
            UUID ownerUserId,
            String merchantStatus,
            String appId,
            String applicationStatus,
            String availableChannels,
            Instant expiresAt,
            Instant consumedAt) { }
    public record MerchantSettlementContextRow(
            UUID merchantId,
            UUID applicationId,
            String merchantNo,
            String merchantName,
            UUID ownerUserId,
            String merchantStatus,
            String appId,
            String applicationStatus,
            String availableChannels) { }
    public record DashboardRow(LocalDate statDate, long paymentAmountCent, long paymentCount,
                               long refundAmountCent, long refundCount, Instant updatedAt) { }
    public record DashboardSummaryRow(long paymentAmountCent, long paymentCount,
                                      long refundAmountCent, long refundCount) { }
    public record MerchantOrderRow(String paymentOrderNo, String merchantOrderNo, String appId, long amountCent,
                                   String currency, String subject, String channel, String allowedChannels,
                                   String status, Instant expiresAt, Instant createdAt, Instant updatedAt,
                                   String refundNo, Long refundAmountCent, String refundStatus, String refundReason) { }
    public record MerchantOrderDetailRow(UUID paymentOrderId, String paymentOrderNo, String merchantOrderNo,
                                         String appId, long amountCent, String currency, String subject, String channel,
                                         String allowedChannels, String status, Instant expiresAt, Instant createdAt,
                                         Instant updatedAt, String refundNo, Long refundAmountCent,
                                         String refundStatus, String refundReason) { }
    public record ChannelDistributionRow(String appId, String channel, long orderCount, long amountCent) { }
}
