package com.minipay.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OpsMapper {
    String MERCHANT_VIEW_COLUMNS = """
            SELECT m.merchant_id, m.merchant_no, m.name, m.short_name,
                   m.contact_name, m.contact_mobile, m.contact_email, m.remark,
                   m.merchant_type, m.mcc_code, m.address, m.latitude, m.longitude,
                   m.shop_images,
                   m.status, m.freeze_reason, m.owner_user_id,
                   m.version, m.created_at, m.updated_at,
                   (SELECT COUNT(1) FROM merchant_application a
                     WHERE a.merchant_id = m.merchant_id) AS application_count,
                   (SELECT COUNT(1) FROM payment_order p
                     WHERE p.merchant_id = m.merchant_id) AS transaction_count
              FROM merchant m
            """;

    String APPLICATION_VIEW_COLUMNS = """
            SELECT a.application_id, a.app_id, a.merchant_id, a.name, a.status,
                   a.version, a.created_at, a.updated_at,
                   m.merchant_no, m.name AS merchant_name, m.status AS merchant_status,
                   (SELECT COUNT(1) FROM payment_order p
                     WHERE p.application_id = a.application_id OR p.app_id = a.app_id)
                     AS transaction_count,
                   (SELECT COUNT(1) FROM payment_order p
                     WHERE (p.application_id = a.application_id OR p.app_id = a.app_id)
                       AND p.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY))
                     AS recent_transaction_count,
                   (SELECT MAX(p.created_at) FROM payment_order p
                     WHERE p.application_id = a.application_id OR p.app_id = a.app_id)
                     AS last_transaction_at,
                   (SELECT COUNT(1) FROM merchant_notification n
                     WHERE n.application_id = a.application_id) AS reference_count
              FROM merchant_application a
              JOIN merchant m ON m.merchant_id = a.merchant_id
            """;

    @Select({"<script>", MERCHANT_VIEW_COLUMNS,
            "<where>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='contactMobile != null'>AND m.contact_mobile = #{contactMobile}</if>",
            "<if test='status != null'>AND m.status = #{status}</if>",
            "</where>",
            "ORDER BY m.created_at DESC, m.merchant_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<MerchantRow> findMerchants(
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("contactMobile") String contactMobile,
            @Param("status") String status,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>", "SELECT COUNT(1) FROM merchant m", "<where>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='contactMobile != null'>AND m.contact_mobile = #{contactMobile}</if>",
            "<if test='status != null'>AND m.status = #{status}</if>",
            "</where>", "</script>"})
    long countMerchants(
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("contactMobile") String contactMobile,
            @Param("status") String status);

    @Select(MERCHANT_VIEW_COLUMNS + " WHERE m.merchant_id = #{merchantId}")
    MerchantRow findMerchant(@Param("merchantId") byte[] merchantId);

    @Insert("""
            INSERT INTO merchant (
              merchant_id, merchant_no, name, short_name, contact_name, contact_mobile,
              contact_email, remark, merchant_type, mcc_code, address,
              latitude, longitude, shop_images,
              status, owner_user_id,
              version, created_at, updated_at
            ) VALUES (
              #{merchantId}, #{merchantNo}, #{name}, #{shortName}, #{contactName},
              #{contactMobile}, #{contactEmail}, #{remark}, #{merchantType}, #{mccCode},
              #{address}, #{latitude}, #{longitude}, #{shopImages},
              #{status}, #{ownerUserId},
              #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    int insertMerchant(MerchantRow merchant);

    @Update("UPDATE merchant SET source = #{source}, updated_at = UTC_TIMESTAMP(6) WHERE merchant_id = #{merchantId}")
    int updateMerchantSource(
            @Param("merchantId") byte[] merchantId,
            @Param("source") String source);

    @Update("""
            UPDATE merchant
               SET name = #{name},
                   short_name = #{shortName},
                   contact_name = #{contactName},
                   contact_mobile = #{contactMobile},
                   contact_email = #{contactEmail},
                   remark = #{remark},
                   merchant_type = #{merchantType},
                   mcc_code = #{mccCode},
                   address = #{address},
                   latitude = #{latitude},
                   longitude = #{longitude},
                   shop_images = #{shopImages},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE merchant_id = #{merchantId} AND version = #{expectedVersion}
            """)
    int updateMerchantProfile(MerchantRow merchant);

    @Update("""
            UPDATE merchant
               SET status = #{status}, version = version + 1, updated_at = #{updatedAt}
             WHERE merchant_id = #{merchantId} AND version = #{expectedVersion}
            """)
    int updateMerchantStatus(
            @Param("merchantId") byte[] merchantId,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE merchant
               SET status = #{status},
                   freeze_reason = #{freezeReason},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE merchant_id = #{merchantId} AND version = #{expectedVersion}
            """)
    int updateMerchantFreeze(
            @Param("merchantId") byte[] merchantId,
            @Param("status") String status,
            @Param("freezeReason") String freezeReason,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM merchant WHERE merchant_id = #{merchantId} AND version = #{version}")
    int deleteMerchant(@Param("merchantId") byte[] merchantId, @Param("version") long version);

    @Select("""
            SELECT
              (SELECT COUNT(1) FROM merchant_application
                WHERE merchant_id = #{merchantId}) AS application_count,
              (SELECT COUNT(1) FROM payment_order
                WHERE merchant_id = #{merchantId}) AS transaction_count
            """)
    DependencyRow dependencies(@Param("merchantId") byte[] merchantId);

    String APPLY_VIEW_COLUMNS = """
            SELECT a.id, a.user_id, a.merchant_type, a.shop_name, a.mcc_code,
                   a.address, a.latitude, a.longitude, a.shop_images, a.contact_name,
                   a.contact_mobile, a.contact_email, a.remark, a.apply_status, a.reject_reason,
                   a.audit_admin_id, a.resultant_merchant_id,
                   a.apply_time, a.audit_time, a.version, a.created_at, a.updated_at
              FROM merchant_apply a
            """;

    @Select({"<script>", APPLY_VIEW_COLUMNS,
            "<where>",
            "<if test='applyStatus != null'>AND a.apply_status = #{applyStatus}</if>",
            "<if test='userId != null'>AND a.user_id = #{userId}</if>",
            "</where>",
            "ORDER BY a.apply_time DESC, a.id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<ApplyRow> findApplies(
            @Param("applyStatus") String applyStatus,
            @Param("userId") byte[] userId,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>", "SELECT COUNT(1) FROM merchant_apply a", "<where>",
            "<if test='applyStatus != null'>AND a.apply_status = #{applyStatus}</if>",
            "<if test='userId != null'>AND a.user_id = #{userId}</if>",
            "</where>", "</script>"})
    long countApplies(
            @Param("applyStatus") String applyStatus,
            @Param("userId") byte[] userId);

    @Select(APPLY_VIEW_COLUMNS + " WHERE a.id = #{id}")
    ApplyRow findApply(@Param("id") long id);

    @Insert("""
            INSERT INTO merchant_apply (
              user_id, merchant_type, shop_name, normalized_shop_name, submission_version,
              mcc_code, address, latitude, longitude, shop_images,
              contact_name, contact_mobile, contact_email, remark,
              apply_status, reject_reason, audit_admin_id, resultant_merchant_id,
              apply_time, audit_time, version, created_at, updated_at
            ) VALUES (
              #{userId}, #{merchantType}, #{shopName}, LOWER(TRIM(#{shopName})), 1,
              #{mccCode}, #{address}, #{latitude}, #{longitude},
              #{shopImages}, #{contactName}, #{contactMobile}, #{contactEmail}, #{remark},
              #{applyStatus}, #{rejectReason}, #{auditAdminId}, #{resultantMerchantId},
              #{applyTime}, #{auditTime}, #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertApply(ApplyRow apply);

    @Insert("""
            INSERT IGNORE INTO merchant_onboarding_guard (
              user_id, apply_id, merchant_id, created_at, updated_at
            ) VALUES (#{userId}, NULL, NULL, #{now}, #{now})
            """)
    int reserveOnboardingOwner(@Param("userId") byte[] userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE merchant_onboarding_guard
               SET apply_id = #{applyId}, updated_at = #{now}
             WHERE user_id = #{userId} AND apply_id IS NULL
            """)
    int bindOnboardingApplication(@Param("userId") byte[] userId,
                                  @Param("applyId") long applyId,
                                  @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM merchant_onboarding_guard
             WHERE user_id = #{userId} AND apply_id = #{applyId}
            """)
    boolean isBoundOnboardingApplication(@Param("userId") byte[] userId,
                                         @Param("applyId") long applyId);

    @Update("""
            UPDATE merchant_onboarding_guard
               SET merchant_id = #{merchantId}, updated_at = #{now}
             WHERE user_id = #{userId} AND apply_id = #{applyId}
               AND (merchant_id IS NULL OR merchant_id = #{merchantId})
            """)
    int bindOnboardingMerchant(@Param("userId") byte[] userId,
                               @Param("applyId") long applyId,
                               @Param("merchantId") byte[] merchantId,
                               @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM merchant_apply
             WHERE user_id = #{userId}
               AND normalized_shop_name = #{normalizedShopName}
               AND id != #{excludingId}
               AND apply_status IN ('DRAFT', 'PENDING', 'SUPPLEMENT')
            """)
    boolean existsOpenApplyByOwnerAndShop(
            @Param("userId") byte[] userId,
            @Param("normalizedShopName") String normalizedShopName,
            @Param("excludingId") long excludingId);

    @Update("""
            UPDATE merchant_apply
               SET merchant_type = #{merchantType},
                   shop_name = #{shopName},
                   normalized_shop_name = LOWER(TRIM(#{shopName})),
                   submission_version = submission_version + 1,
                   mcc_code = #{mccCode},
                   address = #{address},
                   latitude = #{latitude},
                   longitude = #{longitude},
                   shop_images = #{shopImages},
                   contact_name = #{contactName},
                   contact_mobile = #{contactMobile},
                   contact_email = #{contactEmail},
                   remark = #{remark},
                   apply_status = 'PENDING',
                   reject_reason = NULL,
                   audit_admin_id = NULL,
                   resultant_merchant_id = NULL,
                   apply_time = #{applyTime},
                   audit_time = NULL,
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND user_id = #{userId} AND version = #{expectedVersion}
               AND apply_status IN ('SUPPLEMENT', 'REJECTED')
            """)
    int updateApplySubmission(ApplyRow apply);

    @Update("""
            UPDATE merchant_apply
               SET apply_status = #{applyStatus},
                   reject_reason = #{rejectReason},
                   audit_admin_id = #{auditAdminId},
                   resultant_merchant_id = #{resultantMerchantId},
                   audit_time = #{auditTime},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateApplyAudit(ApplyRow apply);

    String APPLICATION_APPLY_VIEW_COLUMNS = """
            SELECT a.id, a.user_id, a.merchant_id, a.name, a.apply_status, a.reject_reason,
                   a.audit_admin_id, a.resultant_application_id,
                   a.apply_time, a.audit_time, a.version, a.created_at, a.updated_at,
                   m.merchant_no, m.name AS merchant_name
              FROM application_apply a
              LEFT JOIN merchant m ON m.merchant_id = a.merchant_id
            """;

    @Select({"<script>", APPLICATION_APPLY_VIEW_COLUMNS,
            "<where>",
            "<if test='userId != null'>AND a.user_id = #{userId}</if>",
            "<if test='applyStatus != null'>AND a.apply_status = #{applyStatus}</if>",
            "</where>",
            "ORDER BY a.apply_time DESC, a.id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<ApplicationApplyRow> findApplicationApplies(
            @Param("userId") byte[] userId,
            @Param("applyStatus") String applyStatus,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>", "SELECT COUNT(1) FROM application_apply a", "<where>",
            "<if test='userId != null'>AND a.user_id = #{userId}</if>",
            "<if test='applyStatus != null'>AND a.apply_status = #{applyStatus}</if>",
            "</where>", "</script>"})
    long countApplicationApplies(
            @Param("userId") byte[] userId,
            @Param("applyStatus") String applyStatus);

    @Select(APPLICATION_APPLY_VIEW_COLUMNS + " WHERE a.id = #{id}")
    ApplicationApplyRow findApplicationApply(@Param("id") long id);

    @Insert("""
            INSERT INTO application_apply (
              user_id, merchant_id, name, apply_status, reject_reason, audit_admin_id,
              resultant_application_id, apply_time, audit_time, version, created_at, updated_at
            ) VALUES (
              #{userId}, #{merchantId}, #{name}, #{applyStatus}, #{rejectReason}, #{auditAdminId},
              #{resultantApplicationId}, #{applyTime}, #{auditTime}, #{version}, #{createdAt},
              #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertApplicationApply(ApplicationApplyRow apply);

    @Update("""
            UPDATE application_apply
               SET apply_status = #{applyStatus},
                   reject_reason = #{rejectReason},
                   audit_admin_id = #{auditAdminId},
                   resultant_application_id = #{resultantApplicationId},
                   audit_time = #{auditTime},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateApplicationApplyAudit(ApplicationApplyRow apply);

    @Update("""
            UPDATE application_apply
               SET name = #{name},
                   apply_status = #{applyStatus},
                   reject_reason = NULL,
                   audit_admin_id = NULL,
                   audit_time = NULL,
                   apply_time = #{applyTime},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateApplicationApplyResubmit(ApplicationApplyRow apply);

    @Select("""
            SELECT COUNT(1) FROM application_apply
             WHERE merchant_id = #{merchantId}
               AND apply_status IN ('PENDING', 'SUPPLEMENT')
            """)
    boolean existsPendingByMerchant(@Param("merchantId") byte[] merchantId);

    @Select("""
            SELECT COUNT(1) FROM application_apply
             WHERE merchant_id = #{merchantId}
               AND name = #{name}
               AND id != #{excludeApplyId}
               AND apply_status IN ('PENDING', 'SUPPLEMENT')
            """)
    boolean existsByName(
            @Param("merchantId") byte[] merchantId,
            @Param("name") String name,
            @Param("excludeApplyId") long excludeApplyId);

    @Select({"<script>", APPLICATION_VIEW_COLUMNS,
            "<where>",
            "<if test='appId != null'>AND a.app_id LIKE CONCAT(#{appId}, '%')</if>",
            "<if test='name != null'>AND a.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='merchantId != null'>AND a.merchant_id = #{merchantId}</if>",
            "<if test='status != null'>AND a.status = #{status}</if>",
            "<if test='unavailable'>AND a.status = 'ACTIVE' AND m.status != 'ACTIVE'</if>",
            "</where>",
            "ORDER BY a.created_at DESC, a.application_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<ApplicationRow> findApplications(
            @Param("appId") String appId,
            @Param("name") String name,
            @Param("merchantId") byte[] merchantId,
            @Param("status") String status,
            @Param("unavailable") Boolean unavailable,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>",
            "SELECT COUNT(1) FROM merchant_application a",
            "JOIN merchant m ON m.merchant_id = a.merchant_id", "<where>",
            "<if test='appId != null'>AND a.app_id LIKE CONCAT(#{appId}, '%')</if>",
            "<if test='name != null'>AND a.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='merchantId != null'>AND a.merchant_id = #{merchantId}</if>",
            "<if test='status != null'>AND a.status = #{status}</if>",
            "<if test='unavailable'>AND a.status = 'ACTIVE' AND m.status != 'ACTIVE'</if>",
            "</where>", "</script>"})
    long countApplications(
            @Param("appId") String appId,
            @Param("name") String name,
            @Param("merchantId") byte[] merchantId,
            @Param("status") String status,
            @Param("unavailable") Boolean unavailable);

    @Select("""
            SELECT
              COUNT(1) AS total_count,
              SUM(CASE WHEN a.status = 'ACTIVE' THEN 1 ELSE 0 END) AS active_count,
              SUM(CASE WHEN a.status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_count,
              SUM(CASE WHEN a.status = 'ACTIVE' AND m.status != 'ACTIVE' THEN 1 ELSE 0 END)
                AS unavailable_count
              FROM merchant_application a
              JOIN merchant m ON m.merchant_id = a.merchant_id
            """)
    ApplicationSummaryRow applicationSummary();

    @Select(APPLICATION_VIEW_COLUMNS + " WHERE a.application_id = #{applicationId}")
    ApplicationRow findApplication(@Param("applicationId") byte[] applicationId);

    @Select("""
            <script>
            SELECT COUNT(1) FROM merchant_application
             WHERE merchant_id = #{merchantId} AND name = #{name}
            <if test='excludingApplicationId != null'>
               AND application_id != #{excludingApplicationId}
            </if>
            </script>
            """)
    long countApplicationName(
            @Param("merchantId") byte[] merchantId,
            @Param("name") String name,
            @Param("excludingApplicationId") byte[] excludingApplicationId);

    @Insert("""
            INSERT INTO merchant_application (
              application_id, app_id, merchant_id, name, status, version, created_at, updated_at
            ) VALUES (
              #{applicationId}, #{appId}, #{merchantId}, #{name}, #{status},
              #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    int insertApplication(ApplicationRow application);

    @Update("""
            UPDATE merchant_application
               SET name = #{name}, version = version + 1, updated_at = #{updatedAt}
             WHERE application_id = #{applicationId} AND version = #{expectedVersion}
            """)
    int updateApplicationName(ApplicationRow application);

    @Update("""
            UPDATE merchant_application
               SET status = #{status}, version = version + 1, updated_at = #{updatedAt}
             WHERE application_id = #{applicationId} AND version = #{expectedVersion}
            """)
    int updateApplicationStatus(
            @Param("applicationId") byte[] applicationId,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM merchant_application WHERE application_id = #{applicationId} AND version = #{version}")
    int deleteApplication(
            @Param("applicationId") byte[] applicationId, @Param("version") long version);

    @Select("""
            SELECT
              (SELECT COUNT(1) FROM payment_order
                WHERE application_id = #{applicationId} OR app_id = #{appId}) AS transaction_count,
              (SELECT COUNT(1) FROM merchant_notification
                WHERE application_id = #{applicationId}) AS reference_count
            """)
    ApplicationDependencyRow applicationDependencies(
            @Param("applicationId") byte[] applicationId, @Param("appId") String appId);

    @Insert("""
            INSERT IGNORE INTO idempotency_record (
              record_id, actor_id, operation, idempotency_key, request_digest, created_at
            ) VALUES (
              #{recordId}, #{actorId}, #{operation}, #{idempotencyKey}, #{requestDigest}, #{createdAt}
            )
            """)
    int insertIdempotency(IdempotencyRow row);

    @Select("""
            SELECT request_digest, response_status, CAST(response_json AS CHAR) AS response_json
              FROM idempotency_record
             WHERE actor_id = #{actorId}
               AND operation = #{operation}
               AND idempotency_key = #{key}
             FOR UPDATE
            """)
    IdempotencyRow lockIdempotency(
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("key") String key);

    @Update("""
            UPDATE idempotency_record
               SET response_status = #{status},
                   response_json = CAST(#{responseJson} AS JSON),
                   completed_at = #{completedAt}
             WHERE actor_id = #{actorId}
               AND operation = #{operation}
               AND idempotency_key = #{key}
            """)
    int completeIdempotency(
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("key") String key,
            @Param("status") int status,
            @Param("responseJson") String responseJson,
            @Param("completedAt") LocalDateTime completedAt);

    @Insert("""
            INSERT INTO operation_audit (
              audit_id, actor_id, action, resource_type, resource_id,
              before_digest, after_digest, result, request_id, occurred_at
            ) VALUES (
              #{auditId}, #{actorId}, #{action}, #{resourceType}, #{resourceId},
              #{beforeDigest}, #{afterDigest}, 'SUCCESS', #{requestId}, #{occurredAt}
            )
            """)
    int insertAudit(AuditRow row);

    @Select("""
            SELECT COALESCE(SUM(submitted_payment_count), 0) AS submitted_payment_count,
                   COALESCE(SUM(successful_payment_count), 0) AS successful_payment_count,
                   COALESCE(SUM(payment_amount_cent), 0) AS payment_amount_cent,
                   COALESCE(SUM(successful_refund_count), 0) AS successful_refund_count,
                   COALESCE(SUM(refund_amount_cent), 0) AS refund_amount_cent,
                   MAX(calculated_at) AS data_as_of
              FROM platform_daily_metric
             WHERE metric_date BETWEEN #{from} AND #{to}
            """)
    MetricAggregateRow aggregate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT metric_date, submitted_payment_count, successful_payment_count,
                   payment_amount_cent, successful_refund_count, refund_amount_cent
              FROM platform_daily_metric
             WHERE metric_date BETWEEN #{from} AND #{to}
             ORDER BY metric_date
            """)
    List<DailyMetricRow> trend(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT COUNT(DISTINCT merchant_id)
              FROM merchant_daily_metric
             WHERE metric_date BETWEEN #{from} AND #{to}
               AND successful_payment_count > 0
            """)
    long activeMerchantCount(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT
              (SELECT COUNT(1) FROM payment_order
                WHERE status = 'PROCESSING' AND updated_at < #{threshold}) AS abnormal_payment_count,
              (SELECT COUNT(1) FROM refund_order
                WHERE status = 'PROCESSING' AND updated_at < #{threshold}) AS abnormal_refund_count,
              (SELECT COUNT(1) FROM transfer_order
                WHERE status = 'PROCESSING' AND updated_at < #{threshold}) AS abnormal_transfer_count,
              (SELECT COUNT(1) FROM merchant_notification
                WHERE status = 'FAILED') AS failed_notification_count
            """)
    PendingRow pending(@Param("threshold") LocalDateTime threshold);

    String PAYMENT_ORDER_VIEW_COLUMNS = """
            SELECT p.pay_order_no, p.merchant_id, p.app_id, p.merchant_order_no,
                   p.amount_cent, p.currency, p.channel, p.subject, p.status,
                   p.payer_user_id, p.created_at, p.updated_at,
                   m.merchant_no, m.name AS merchant_name,
                   (SELECT pa.failure_code FROM payment_attempt pa
                     WHERE pa.pay_order_id = p.pay_order_id
                     ORDER BY pa.created_at DESC LIMIT 1) AS failure_code
              FROM payment_order p
              JOIN merchant m ON m.merchant_id = p.merchant_id
            """;

    String REFUND_ORDER_VIEW_COLUMNS = """
            SELECT r.refund_order_no, r.pay_order_id, r.amount_cent, r.status,
                   r.reason, r.created_at, r.updated_at,
                   p.pay_order_no AS payment_order_no, p.merchant_id,
                   m.merchant_no, m.name AS merchant_name
              FROM refund_order r
              JOIN payment_order p ON p.pay_order_id = r.pay_order_id
              JOIN merchant m ON m.merchant_id = p.merchant_id
            """;

    String TRANSFER_ORDER_VIEW_COLUMNS = """
            SELECT o.transfer_no, o.amount_cent, o.status, o.failure_code,
                   o.created_at, o.updated_at,
                   i.payer_user_id, i.receiver_user_id
              FROM transfer_order o
              JOIN transfer_intent i ON i.intent_id = o.intent_id
            """;

    String RECHARGE_ORDER_VIEW_COLUMNS = """
            SELECT r.recharge_no, r.user_id, r.amount_cent, r.channel, r.status,
                   r.failure_code, r.created_at, r.updated_at,
                   c.bank_name, c.masked_card_no
              FROM recharge_order r
              LEFT JOIN bank_card c ON c.card_id = r.bank_card_id
            """;

    String WITHDRAWAL_ORDER_VIEW_COLUMNS = """
            SELECT w.withdrawal_no, w.user_id, w.amount_cent, w.status,
                   w.bank_request_no, w.failure_code, w.created_at, w.updated_at,
                   c.bank_name, c.masked_card_no
              FROM withdrawal_order w
              JOIN bank_card c ON c.card_id = w.bank_card_id
            """;

    @Select({"<script>", PAYMENT_ORDER_VIEW_COLUMNS,
            "<where>",
            "<if test='merchantId != null'>AND p.merchant_id = #{merchantId}</if>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='appId != null'>AND p.app_id = #{appId}</if>",
            "<if test='status != null'>AND p.status = #{status}</if>",
            "<if test='from != null'>AND p.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND p.created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY p.created_at DESC, p.pay_order_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<PaymentOrderRow> findPayments(
            @Param("merchantId") byte[] merchantId,
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("appId") String appId,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>",
            "SELECT COUNT(1) FROM payment_order p JOIN merchant m ON m.merchant_id = p.merchant_id",
            "<where>",
            "<if test='merchantId != null'>AND p.merchant_id = #{merchantId}</if>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='appId != null'>AND p.app_id = #{appId}</if>",
            "<if test='status != null'>AND p.status = #{status}</if>",
            "<if test='from != null'>AND p.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND p.created_at &lt; #{to}</if>",
            "</where>", "</script>"})
    long countPayments(
            @Param("merchantId") byte[] merchantId,
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("appId") String appId,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select(PAYMENT_ORDER_VIEW_COLUMNS + " WHERE p.pay_order_no = #{paymentOrderNo} LIMIT 1")
    PaymentOrderRow findPaymentByNo(@Param("paymentOrderNo") String paymentOrderNo);

    @Select({"<script>", REFUND_ORDER_VIEW_COLUMNS,
            "<where>",
            "<if test='merchantId != null'>AND p.merchant_id = #{merchantId}</if>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='status != null'>AND r.status = #{status}</if>",
            "<if test='from != null'>AND r.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND r.created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY r.created_at DESC, r.refund_order_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<RefundOrderRow> findRefunds(
            @Param("merchantId") byte[] merchantId,
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>",
            "SELECT COUNT(1) FROM refund_order r",
            "JOIN payment_order p ON p.pay_order_id = r.pay_order_id",
            "JOIN merchant m ON m.merchant_id = p.merchant_id",
            "<where>",
            "<if test='merchantId != null'>AND p.merchant_id = #{merchantId}</if>",
            "<if test='merchantNo != null'>AND m.merchant_no LIKE CONCAT(#{merchantNo}, '%')</if>",
            "<if test='name != null'>AND m.name LIKE CONCAT('%', #{name}, '%')</if>",
            "<if test='status != null'>AND r.status = #{status}</if>",
            "<if test='from != null'>AND r.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND r.created_at &lt; #{to}</if>",
            "</where>", "</script>"})
    long countRefunds(
            @Param("merchantId") byte[] merchantId,
            @Param("merchantNo") String merchantNo,
            @Param("name") String name,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select(REFUND_ORDER_VIEW_COLUMNS + " WHERE r.refund_order_no = #{refundOrderNo} LIMIT 1")
    RefundOrderRow findRefundByNo(@Param("refundOrderNo") String refundOrderNo);

    @Select({"<script>", TRANSFER_ORDER_VIEW_COLUMNS,
            "<where>",
            "<if test='transferNo != null'>AND o.transfer_no = #{transferNo}</if>",
            "<if test='status != null'>AND o.status = #{status}</if>",
            "<if test='from != null'>AND o.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND o.created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY o.created_at DESC, o.transfer_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<TransferOrderRow> findTransfers(
            @Param("transferNo") String transferNo,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select({"<script>",
            "SELECT COUNT(1) FROM transfer_order o JOIN transfer_intent i ON i.intent_id = o.intent_id",
            "<where>",
            "<if test='transferNo != null'>AND o.transfer_no = #{transferNo}</if>",
            "<if test='status != null'>AND o.status = #{status}</if>",
            "<if test='from != null'>AND o.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND o.created_at &lt; #{to}</if>",
            "</where>", "</script>"})
    long countTransfers(
            @Param("transferNo") String transferNo,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select(TRANSFER_ORDER_VIEW_COLUMNS + " WHERE o.transfer_no = #{transferNo} LIMIT 1")
    TransferOrderRow findTransferByNo(@Param("transferNo") String transferNo);

    @Select({"<script>", RECHARGE_ORDER_VIEW_COLUMNS,
            "<where>",
            "<if test='rechargeNo != null'>AND r.recharge_no = #{rechargeNo}</if>",
            "<if test='status != null'>AND r.status = #{status}</if>",
            "<if test='from != null'>AND r.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND r.created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY r.created_at DESC, r.recharge_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<RechargeOrderRow> findRecharges(
            @Param("rechargeNo") String rechargeNo, @Param("status") String status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("size") int size, @Param("offset") int offset);

    @Select({"<script>", "SELECT COUNT(1) FROM recharge_order r", "<where>",
            "<if test='rechargeNo != null'>AND r.recharge_no = #{rechargeNo}</if>",
            "<if test='status != null'>AND r.status = #{status}</if>",
            "<if test='from != null'>AND r.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND r.created_at &lt; #{to}</if>",
            "</where>", "</script>"})
    long countRecharges(@Param("rechargeNo") String rechargeNo, @Param("status") String status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select(RECHARGE_ORDER_VIEW_COLUMNS + " WHERE r.recharge_no = #{rechargeNo} LIMIT 1")
    RechargeOrderRow findRechargeByNo(@Param("rechargeNo") String rechargeNo);

    @Select({"<script>", WITHDRAWAL_ORDER_VIEW_COLUMNS,
            "<where>",
            "<if test='withdrawalNo != null'>AND w.withdrawal_no = #{withdrawalNo}</if>",
            "<if test='status != null'>AND w.status = #{status}</if>",
            "<if test='from != null'>AND w.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND w.created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY w.created_at DESC, w.withdrawal_id DESC LIMIT #{size} OFFSET #{offset}",
            "</script>"})
    List<WithdrawalOrderRow> findWithdrawals(
            @Param("withdrawalNo") String withdrawalNo, @Param("status") String status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("size") int size, @Param("offset") int offset);

    @Select({"<script>", "SELECT COUNT(1) FROM withdrawal_order w", "<where>",
            "<if test='withdrawalNo != null'>AND w.withdrawal_no = #{withdrawalNo}</if>",
            "<if test='status != null'>AND w.status = #{status}</if>",
            "<if test='from != null'>AND w.created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND w.created_at &lt; #{to}</if>",
            "</where>", "</script>"})
    long countWithdrawals(@Param("withdrawalNo") String withdrawalNo, @Param("status") String status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select(WITHDRAWAL_ORDER_VIEW_COLUMNS + " WHERE w.withdrawal_no = #{withdrawalNo} LIMIT 1")
    WithdrawalOrderRow findWithdrawalByNo(@Param("withdrawalNo") String withdrawalNo);

    @Select("""
            SELECT card_id,user_id,provider,bank_name,card_type,masked_card_no,holder_name,status,
                   verified_at,created_at,updated_at
              FROM bank_card WHERE user_id=#{userId}
             ORDER BY created_at DESC
            """)
    List<AdminBankCardRow> findAdminBankCards(@Param("userId") byte[] userId);

    @Insert("""
            INSERT IGNORE INTO metric_projected_event (event_id, event_type, projected_at)
            VALUES (#{eventId}, #{eventType}, #{projectedAt})
            """)
    int markMetricEvent(
            @Param("eventId") byte[] eventId,
            @Param("eventType") String eventType,
            @Param("projectedAt") LocalDateTime projectedAt);

    @Insert("""
            INSERT INTO platform_daily_metric (
              metric_date, submitted_payment_count, successful_payment_count,
              payment_amount_cent, successful_refund_count, refund_amount_cent, calculated_at
            ) VALUES (
              #{date}, #{submittedCount}, #{successfulCount}, #{amountCent}, 0, 0, #{at}
            )
            ON DUPLICATE KEY UPDATE
              submitted_payment_count = submitted_payment_count + VALUES(submitted_payment_count),
              successful_payment_count = successful_payment_count + VALUES(successful_payment_count),
              payment_amount_cent = payment_amount_cent + VALUES(payment_amount_cent),
              calculated_at = VALUES(calculated_at)
            """)
    int addPlatformPayment(
            @Param("date") LocalDate date,
            @Param("submittedCount") long submittedCount,
            @Param("successfulCount") long successfulCount,
            @Param("amountCent") long amountCent,
            @Param("at") LocalDateTime at);

    @Insert("""
            INSERT INTO merchant_daily_metric (
              metric_date, merchant_id, successful_payment_count, payment_amount_cent,
              successful_refund_count, refund_amount_cent, calculated_at
            ) VALUES (
              #{date}, #{merchantId}, #{successfulCount}, #{amountCent}, 0, 0, #{at}
            )
            ON DUPLICATE KEY UPDATE
              successful_payment_count = successful_payment_count + VALUES(successful_payment_count),
              payment_amount_cent = payment_amount_cent + VALUES(payment_amount_cent),
              calculated_at = VALUES(calculated_at)
            """)
    int addMerchantPayment(
            @Param("date") LocalDate date,
            @Param("merchantId") byte[] merchantId,
            @Param("successfulCount") long successfulCount,
            @Param("amountCent") long amountCent,
            @Param("at") LocalDateTime at);

    @Insert("""
            INSERT INTO platform_daily_metric (
              metric_date, submitted_payment_count, successful_payment_count,
              payment_amount_cent, successful_refund_count, refund_amount_cent, calculated_at
            ) VALUES (
              #{date}, 0, 0, 0, #{successfulCount}, #{amountCent}, #{at}
            )
            ON DUPLICATE KEY UPDATE
              successful_refund_count = successful_refund_count + VALUES(successful_refund_count),
              refund_amount_cent = refund_amount_cent + VALUES(refund_amount_cent),
              calculated_at = VALUES(calculated_at)
            """)
    int addPlatformRefund(
            @Param("date") LocalDate date,
            @Param("successfulCount") long successfulCount,
            @Param("amountCent") long amountCent,
            @Param("at") LocalDateTime at);

    @Insert("""
            INSERT INTO merchant_daily_metric (
              metric_date, merchant_id, successful_payment_count, payment_amount_cent,
              successful_refund_count, refund_amount_cent, calculated_at
            ) VALUES (
              #{date}, #{merchantId}, 0, 0, #{successfulCount}, #{amountCent}, #{at}
            )
            ON DUPLICATE KEY UPDATE
              successful_refund_count = successful_refund_count + VALUES(successful_refund_count),
              refund_amount_cent = refund_amount_cent + VALUES(refund_amount_cent),
              calculated_at = VALUES(calculated_at)
            """)
    int addMerchantRefund(
            @Param("date") LocalDate date,
            @Param("merchantId") byte[] merchantId,
            @Param("successfulCount") long successfulCount,
            @Param("amountCent") long amountCent,
            @Param("at") LocalDateTime at);

    class ApplyRow {
        public long id;
        public byte[] userId;
        public String merchantType;
        public String shopName;
        public String mccCode;
        public String address;
        public BigDecimal latitude;
        public BigDecimal longitude;
        public String shopImages;
        public String contactName;
        public String contactMobile;
        public String contactEmail;
        public String remark;
        public String applyStatus;
        public String rejectReason;
        public String auditAdminId;
        public byte[] resultantMerchantId;
        public LocalDateTime applyTime;
        public LocalDateTime auditTime;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public long expectedVersion;
    }

    class ApplicationApplyRow {
        public long id;
        public byte[] userId;
        public byte[] merchantId;
        public String name;
        public String applyStatus;
        public String rejectReason;
        public String auditAdminId;
        public byte[] resultantApplicationId;
        public LocalDateTime applyTime;
        public LocalDateTime auditTime;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public String merchantNo;
        public String merchantName;
        public long expectedVersion;
    }

    class MerchantRow {
        public byte[] merchantId;
        public String merchantNo;
        public String name;
        public String shortName;
        public String contactName;
        public String contactMobile;
        public String contactEmail;
        public String remark;
        public String merchantType;
        public String mccCode;
        public String address;
        public BigDecimal latitude;
        public BigDecimal longitude;
        public String shopImages;
        public String status;
        public String freezeReason;
        public byte[] ownerUserId;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public long applicationCount;
        public long transactionCount;
        public long expectedVersion;
    }

    class DependencyRow {
        public long applicationCount;
        public long transactionCount;
    }

    class ApplicationRow {
        public byte[] applicationId;
        public String appId;
        public byte[] merchantId;
        public String merchantNo;
        public String merchantName;
        public String merchantStatus;
        public String name;
        public String status;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public long transactionCount;
        public long recentTransactionCount;
        public LocalDateTime lastTransactionAt;
        public long referenceCount;
        public long expectedVersion;
    }

    class ApplicationDependencyRow {
        public long transactionCount;
        public long referenceCount;
    }

    class ApplicationSummaryRow {
        public long totalCount;
        public long activeCount;
        public long disabledCount;
        public long unavailableCount;
    }

    class IdempotencyRow {
        public byte[] recordId;
        public String actorId;
        public String operation;
        public String idempotencyKey;
        public String requestDigest;
        public Integer responseStatus;
        public String responseJson;
        public LocalDateTime createdAt;
    }

    class AuditRow {
        public byte[] auditId;
        public String actorId;
        public String action;
        public String resourceType;
        public byte[] resourceId;
        public String beforeDigest;
        public String afterDigest;
        public String requestId;
        public LocalDateTime occurredAt;
    }

    class MetricAggregateRow {
        public long submittedPaymentCount;
        public long successfulPaymentCount;
        public long paymentAmountCent;
        public long successfulRefundCount;
        public long refundAmountCent;
        public LocalDateTime dataAsOf;
    }

    class DailyMetricRow {
        public LocalDate metricDate;
        public long submittedPaymentCount;
        public long successfulPaymentCount;
        public long paymentAmountCent;
        public long successfulRefundCount;
        public long refundAmountCent;
    }

    class PendingRow {
        public long abnormalPaymentCount;
        public long abnormalRefundCount;
        public long abnormalTransferCount;
        public long failedNotificationCount;
    }

    class PaymentOrderRow {
        public String payOrderNo;
        public byte[] merchantId;
        public String appId;
        public String merchantOrderNo;
        public long amountCent;
        public String currency;
        public String channel;
        public String subject;
        public String status;
        public byte[] payerUserId;
        public String failureCode;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public String merchantNo;
        public String merchantName;
    }

    class RefundOrderRow {
        public String refundOrderNo;
        public byte[] payOrderId;
        public String paymentOrderNo;
        public byte[] merchantId;
        public long amountCent;
        public String status;
        public String reason;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public String merchantNo;
        public String merchantName;
    }

    class TransferOrderRow {
        public String transferNo;
        public byte[] payerUserId;
        public byte[] receiverUserId;
        public long amountCent;
        public String status;
        public String failureCode;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    class RechargeOrderRow {
        public String rechargeNo;
        public byte[] userId;
        public long amountCent;
        public String channel;
        public String status;
        public String failureCode;
        public String bankName;
        public String maskedCardNo;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    class WithdrawalOrderRow {
        public String withdrawalNo;
        public byte[] userId;
        public long amountCent;
        public String status;
        public String bankRequestNo;
        public String failureCode;
        public String bankName;
        public String maskedCardNo;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    class AdminBankCardRow {
        public byte[] cardId;
        public byte[] userId;
        public String provider;
        public String bankName;
        public String cardType;
        public String maskedCardNo;
        public String holderName;
        public String status;
        public LocalDateTime verifiedAt;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }
}
