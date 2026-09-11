package co.yixiang.yshop.module.minipay.service;

import static co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.*;

import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.order.controller.app.order.param.AppOrderParam;
import co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniPayFoodService {
    private static final Logger log = LoggerFactory.getLogger(MiniPayFoodService.class);
    private static final long MAX_AMOUNT_CENT = 100_000_000L;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;
    private final AppStoreOrderService orders;
    private final MiniPayAvatarService avatars;
    private final long tenantId;

    public MiniPayFoodService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AppStoreOrderService orders,
            MiniPayAvatarService avatars,
            @Value("${yshop.minipay.tenant-id:1}") long tenantId) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.orders = orders;
        this.avatars = avatars;
        this.tenantId = tenantId;
    }

    @Transactional
    public IdentityView resolveIdentity(IdentityRequest request) {
        UUID subject = subject(request.subject());
        String phone = normalizedPhone(request.phone());
        String lockName = "minipay_identity_" + shortHash(subject + "\n" + phone);
        Integer locked = jdbc.queryForObject("SELECT GET_LOCK(?, 5)", Integer.class, lockName);
        if (locked == null || locked != 1) throw conflict("MINIPAY_IDENTITY_BUSY");
        try {
            IdentityRow existing = identity(subject.toString());
            if (existing != null) {
                List<MemberRow> matches = membersByPhone(phone);
                if (matches.size() > 1 || (!matches.isEmpty()
                        && matches.get(0).memberId() != existing.memberId())) {
                    throw conflict("YSHOP_PHONE_ACCOUNT_CONFLICT");
                }
                if (!matches.isEmpty() && matches.get(0).status() != 1) {
                    throw conflict("YSHOP_ACCOUNT_DISABLED");
                }
                if (request.profileVersion() > existing.profileVersion()) {
                    String avatar = tryCopyAvatar(
                            request.avatarFetchUrl(), subject.toString(), request.profileVersion());
                    jdbc.update("""
                            UPDATE yshop_user
                               SET nickname = ?, mobile = ?, avatar = COALESCE(?, avatar),
                                   updater = 'minipay', update_time = NOW(6)
                             WHERE id = ? AND deleted = b'0' AND tenant_id = ?
                            """, limitedNickname(request.nickname()), phone, avatar,
                            existing.memberId(), tenantId);
                    jdbc.update("""
                            UPDATE yshop_minipay_external_identity
                               SET profile_version = ?, last_profile_synced_at = NOW(6), updated_at = NOW(6)
                             WHERE provider = 'MINIPAY' AND subject = ?
                            """, request.profileVersion(), subject.toString());
                }
                return new IdentityView(
                        "MINIPAY", subject.toString(), existing.memberId(), existing.username(), false);
            }
            List<MemberRow> matches = membersByPhone(phone);
            if (matches.size() > 1) throw conflict("YSHOP_PHONE_ACCOUNT_CONFLICT");
            if (matches.size() == 1) {
                MemberRow member = matches.get(0);
                if (member.status() != 1) throw conflict("YSHOP_ACCOUNT_DISABLED");
                List<String> boundSubjects = jdbc.query("""
                        SELECT subject FROM yshop_minipay_external_identity
                         WHERE provider = 'MINIPAY' AND member_id = ?
                        """, (rs, ignored) -> rs.getString(1), member.memberId());
                if (!boundSubjects.isEmpty() && !subject.toString().equals(boundSubjects.get(0))) {
                    throw conflict("YSHOP_ACCOUNT_ALREADY_BOUND");
                }
                if (boundSubjects.isEmpty()) {
                    jdbc.update("""
                            INSERT INTO yshop_minipay_external_identity
                              (provider, subject, member_id, profile_version, last_profile_synced_at,
                               created_at, updated_at)
                            VALUES ('MINIPAY', ?, ?, ?, NOW(6), NOW(6), NOW(6))
                            """, subject.toString(), member.memberId(), request.profileVersion());
                }
                return new IdentityView(
                        "MINIPAY", subject.toString(), member.memberId(), member.username(), false);
            }
            String username = "minipay_" + subject.toString().replace("-", "");
            String nickname = limitedNickname(request.nickname());
            String avatar = tryCopyAvatar(
                    request.avatarFetchUrl(), subject.toString(), request.profileVersion());
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO yshop_user
                          (username, password, nickname, avatar, mobile, status, user_type, login_type,
                           now_money, brokerage_price, integral, sign_num, level, is_promoter,
                           addres, deleted, tenant_id, creator, create_time, updater, update_time)
                        VALUES (?, NULL, ?, ?, ?, 1, 'minipay', 'minipay',
                                0.00, 0.00, 0.00, 0, 0, 0, '', b'0', ?, 'minipay', NOW(6), 'minipay', NOW(6))
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, username);
                statement.setString(2, nickname);
                statement.setString(3, avatar);
                statement.setString(4, phone);
                statement.setLong(5, tenantId);
                return statement;
            }, key);
            long memberId = key.getKey().longValue();
            jdbc.update("""
                    INSERT INTO yshop_minipay_external_identity
                      (provider, subject, member_id, profile_version, last_profile_synced_at,
                       created_at, updated_at)
                    VALUES ('MINIPAY', ?, ?, ?, NOW(6), NOW(6), NOW(6))
                    """, subject.toString(), memberId, request.profileVersion());
            return new IdentityView("MINIPAY", subject.toString(), memberId, username, true);
        } finally {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
        }
    }

    public IdentityView identityDetails(String rawSubject) {
        UUID subject = subject(rawSubject);
        IdentityRow identity = identity(subject.toString());
        if (identity == null) throw notFound("MINIPAY_IDENTITY_NOT_FOUND");
        return new IdentityView(
                "MINIPAY", subject.toString(), identity.memberId(), identity.username(), false);
    }

    @Transactional
    public void detachIdentity(String rawSubject) {
        UUID subject = subject(rawSubject);
        jdbc.update("""
                DELETE FROM yshop_minipay_external_identity
                 WHERE provider = 'MINIPAY' AND subject = ?
                """, subject.toString());
    }

    private List<MemberRow> membersByPhone(String phone) {
        return jdbc.query("""
                SELECT id, username, status FROM yshop_user
                 WHERE mobile = ? AND deleted = b'0' AND tenant_id = ?
                 ORDER BY id
                """, (rs, ignored) -> new MemberRow(
                        rs.getLong("id"), rs.getString("username"), rs.getInt("status")),
                phone, tenantId);
    }

    static String normalizedPhone(String value) {
        String phone = value == null ? "" : value.replaceAll("\\s+", "");
        if (!phone.matches("^1[3-9]\\d{9}$")) throw conflict("YSHOP_MOBILE_INVALID");
        return phone;
    }

    static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public List<StoreView> nearbyStores(
            double longitude, double latitude, String fulfillmentType, double radiusKm) {
        coordinates(longitude, latitude);
        String fulfillment = fulfillment(fulfillmentType);
        double cappedRadius = Math.min(Math.max(radiusKm, 0.1),
                "PICKUP".equals(fulfillment) ? 10.0 : 50.0);
        return jdbc.query("""
                SELECT q.id, q.name, q.image, q.address, q.distance_km,
                       q.min_price, q.delivery_price, q.delivery_distance
                  FROM (
                    SELECT s.id, s.name, s.image, s.address, s.min_price, s.delivery_price,
                           s.distance AS delivery_distance,
                           6371 * 2 * ASIN(SQRT(
                             POWER(SIN(RADIANS(CAST(s.lat AS DECIMAL(12,8)) - ?) / 2), 2)
                             + COS(RADIANS(?)) * COS(RADIANS(CAST(s.lat AS DECIMAL(12,8))))
                             * POWER(SIN(RADIANS(CAST(s.lng AS DECIMAL(12,8)) - ?) / 2), 2)
                           )) AS distance_km
                      FROM yshop_store_shop s
                     WHERE s.deleted = b'0' AND s.status = 1
                       AND ((TIME(s.start_time) <= TIME(s.end_time)
                             AND TIME(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'))
                                 BETWEEN TIME(s.start_time) AND TIME(s.end_time))
                         OR (TIME(s.start_time) > TIME(s.end_time)
                             AND (TIME(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')) >= TIME(s.start_time)
                               OR TIME(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')) <= TIME(s.end_time))))
                  ) q
                 WHERE q.distance_km <= ?
                   AND (? = 'PICKUP' OR (q.delivery_distance > 0 AND q.distance_km <= q.delivery_distance))
                 ORDER BY q.distance_km ASC, q.id ASC
                 LIMIT 20
                """, (rs, ignored) -> new StoreView(
                rs.getLong("id"), rs.getString("name"), rs.getString("image"),
                rs.getString("address"), Math.round(rs.getDouble("distance_km") * 1000),
                true, "PICKUP".equals(fulfillment)
                        || rs.getDouble("distance_km") <= rs.getInt("delivery_distance"),
                toCent(rs.getBigDecimal("min_price")),
                toCent(rs.getBigDecimal("delivery_price"))),
                latitude, latitude, longitude, cappedRadius, fulfillment);
    }

    public List<StoreView> nearbyStoresForAddress(
            String subject, long addressId, String fulfillmentType) {
        long memberId = requireMember(subject(subject).toString());
        AddressRow address = requireAddress(memberId, addressId);
        double serverRadius = "PICKUP".equalsIgnoreCase(fulfillmentType) ? 10.0 : 50.0;
        return nearbyStores(address.longitude(), address.latitude(), fulfillmentType, serverRadius);
    }

    public List<ProductView> menu(long shopId) {
        requireStore(shopId, false);
        record ProductRow(long id, String name, String description, String image) { }
        List<ProductRow> products = jdbc.query("""
                SELECT p.id, p.store_name, p.store_info, p.image
                  FROM yshop_store_product p
                 WHERE p.shop_id = ? AND p.deleted = b'0' AND p.is_show = 1
                 ORDER BY p.sort DESC, p.id ASC
                """, (rs, ignored) -> new ProductRow(rs.getLong("id"), rs.getString("store_name"),
                rs.getString("store_info"), rs.getString("image")), shopId);
        Map<Long, List<SkuView>> skus = jdbc.query("""
                SELECT v.id, v.product_id, v.sku, v.price, v.stock, COALESCE(v.image, p.image) AS image
                  FROM yshop_store_product_attr_value v
                  JOIN yshop_store_product p ON p.id = v.product_id
                 WHERE p.shop_id = ? AND p.deleted = b'0' AND p.is_show = 1
                 ORDER BY v.product_id, v.id
                """, rs -> {
            Map<Long, List<SkuView>> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getLong("product_id"), ignored -> new ArrayList<>())
                        .add(new SkuView(rs.getLong("id"), rs.getString("sku"),
                                toCent(rs.getBigDecimal("price")), rs.getInt("stock"),
                                rs.getInt("stock") > 0, rs.getString("image")));
            }
            return grouped;
        }, shopId);
        return products.stream().map(product -> new ProductView(
                product.id(), product.name(), product.description(), product.image(),
                skus.getOrDefault(product.id(), List.of()))).toList();
    }

    public List<AddressView> addresses(String subject) {
        long memberId = requireMember(subject);
        return jdbc.query("""
                SELECT id, real_name, phone, address, detail, is_default,
                       CAST(longitude AS DECIMAL(12,8)) AS longitude,
                       CAST(latitude AS DECIMAL(12,8)) AS latitude
                  FROM yshop_user_address
                 WHERE uid = ? AND deleted = b'0'
                 ORDER BY is_default DESC, id DESC
                """, (rs, ignored) -> new AddressView(
                rs.getLong("id"), maskAddress(rs.getString("address"), rs.getString("detail")),
                rs.getBoolean("is_default"), maskName(rs.getString("real_name")),
                maskPhone(rs.getString("phone")), rs.getDouble("longitude"),
                rs.getDouble("latitude")), memberId);
    }

    public String subjectForMember(long memberId) {
        List<String> subjects = jdbc.query("""
                SELECT subject FROM yshop_minipay_external_identity
                 WHERE provider = 'MINIPAY' AND member_id = ?
                """, (rs, ignored) -> rs.getString(1), memberId);
        if (subjects.isEmpty()) throw notFound("MINIPAY_IDENTITY_NOT_FOUND");
        return subjects.get(0);
    }

    @Transactional
    public QuoteView createQuote(QuoteRequest request) {
        UUID subject = subject(request.subject());
        long memberId = requireMember(subject.toString());
        String fulfillment = fulfillment(request.fulfillmentType());
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 50) {
            throw invalid("MINIPAY_QUOTE_ITEMS_INVALID");
        }
        if (request.items().stream().map(QuoteItemRequest::skuId).distinct().count()
                != request.items().size()) throw invalid("MINIPAY_DUPLICATE_SKU");
        StoreRow store = requireStore(request.shopId(), true);
        AddressRow address = null;
        if ("TAKEOUT".equals(fulfillment)) {
            if (request.addressId() == null) throw invalid("MINIPAY_ADDRESS_REQUIRED");
            address = requireAddress(memberId, request.addressId());
            if (store.deliveryDistanceKm() <= 0
                    || haversineKm(store.longitude(), store.latitude(), address.longitude(), address.latitude())
                    > store.deliveryDistanceKm()) throw invalid("MINIPAY_OUTSIDE_DELIVERY_RANGE");
        }
        List<QuoteItem> items = new ArrayList<>();
        long subtotal = 0;
        for (QuoteItemRequest requested : request.items()) {
            if (requested.quantity() < 1 || requested.quantity() > 99) {
                throw invalid("MINIPAY_QUANTITY_INVALID");
            }
            SkuRow sku = requireSku(request.shopId(), requested.skuId());
            if (sku.stock() < requested.quantity()) throw conflict("MINIPAY_SKU_OUT_OF_STOCK");
            long line = Math.multiplyExact(sku.priceCent(), requested.quantity());
            subtotal = Math.addExact(subtotal, line);
            items.add(new QuoteItem(sku.id(), sku.productId(), sku.sku(), sku.name(), sku.image(),
                    requested.quantity(), sku.priceCent(), line));
        }
        if (!meetsMinimumOrder(fulfillment, subtotal, store.minimumOrderCent())) {
            throw invalid("MINIPAY_MINIMUM_ORDER_NOT_MET");
        }
        long delivery = "TAKEOUT".equals(fulfillment) ? store.deliveryFeeCent() : 0;
        long amount = Math.addExact(subtotal, delivery);
        if (amount <= 0 || amount > MAX_AMOUNT_CENT) throw invalid("MINIPAY_AMOUNT_OUT_OF_RANGE");
        UUID quoteId = UUID.randomUUID();
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO yshop_minipay_checkout_quote
                  (quote_id, subject, member_id, shop_id, address_id, fulfillment_type,
                   items_json, address_snapshot_json, subtotal_cent, delivery_fee_cent,
                   amount_cent, currency, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, 'CNY', ?, NOW(6))
                """, quoteId.toString(), subject.toString(), memberId, request.shopId(), request.addressId(),
                fulfillment, writeJson(items), address == null ? null : writeJson(address),
                subtotal, delivery, amount, expires);
        return new QuoteView(quoteId.toString(), store.id(), store.name(), fulfillment,
                items, subtotal, delivery, amount, "CNY", expires.toString());
    }

    @Transactional
    public OrderView createOrder(CreateOrderRequest request, String idempotencyKey) {
        UUID subject = subject(request.subject());
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalid("MINIPAY_IDEMPOTENCY_KEY_REQUIRED");
        }
        OrderView existing = findByIdempotency(subject.toString(), idempotencyKey);
        if (existing != null) return existing;
        QuoteRow quote = lockQuote(request.quoteId());
        if (!quote.subject().equals(subject.toString())) throw notFound("MINIPAY_QUOTE_NOT_FOUND");
        if (quote.consumedAt() != null || !quote.expiresAt().isAfter(Instant.now())) {
            throw conflict("MINIPAY_QUOTE_EXPIRED");
        }
        List<QuoteItem> items = readItems(quote.itemsJson());
        validateQuote(quote, items);
        AppOrderParam param = new AppOrderParam();
        param.setShopId(Long.toString(quote.shopId()));
        param.setAddressId(quote.addressId() == null ? null : Long.toString(quote.addressId()));
        param.setCouponId("");
        param.setOrderType("TAKEOUT".equals(quote.fulfillment()) ? "takeout" : "takein");
        param.setRemark(request.remark() == null ? "" : request.remark());
        param.setGettime(20);
        param.setProductId(items.stream().map(i -> Long.toString(i.productId())).toList());
        param.setSpec(items.stream().map(i -> i.sku().replace(",", "|")).toList());
        param.setNumber(items.stream().map(i -> Integer.toString(i.quantity())).toList());
        param.setPayType("minipay");
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            Map<String, Object> created = orders.createOrder(quote.memberId(), param);
            String orderNo = String.valueOf(created.get("orderId"));
            UUID orderRef = UUID.randomUUID();
            Instant expires = Instant.now().plus(15, ChronoUnit.MINUTES);
            jdbc.update("""
                    INSERT INTO yshop_minipay_payment
                      (order_ref_id, quote_id, subject, member_id, yshop_order_no, idempotency_key,
                       amount_cent, currency, payment_status, refund_status, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'CNY', 'UNPAID', 'NONE', ?, NOW(6), NOW(6))
                    """, orderRef.toString(), quote.quoteId(), subject.toString(), quote.memberId(), orderNo,
                    idempotencyKey, quote.amountCent(), expires);
            jdbc.update("UPDATE yshop_minipay_checkout_quote SET consumed_at = NOW(6) WHERE quote_id = ?",
                    quote.quoteId());
            return requireOrder(subject.toString(), orderRef.toString(), true);
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
    }

    public List<OrderView> listOrders(String subject, int page, int size) {
        UUID user = subject(subject);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return queryOrders("p.subject = ? ORDER BY p.created_at DESC LIMIT ? OFFSET ?",
                user.toString(), safeSize, safePage * safeSize);
    }

    public OrderView getOrder(String subject, String orderRefId) {
        return requireOrder(subject(subject).toString(), orderRefId, true);
    }

    @Transactional
    public EventResult paymentResult(String orderNo, PaymentResultRequest request) {
        if (!"PAID".equals(request.status())) throw invalid("MINIPAY_PAYMENT_STATUS_INVALID");
        if (!"CNY".equals(request.currency())) throw invalid("MINIPAY_CURRENCY_INVALID");
        PaymentRow payment = lockPayment(orderNo, request.subject());
        if (payment.amountCent() != request.amountCent()) throw conflict("MINIPAY_PAYMENT_AMOUNT_MISMATCH");
        if (!claimEvent(request.eventId(), "payment.food-order.succeeded", orderNo, request)) {
            return new EventResult(true, payment.paymentStatus());
        }
        if (payment.paymentOrderId() != null
                && !payment.paymentOrderId().equals(request.paymentOrderId())) {
            throw conflict("MINIPAY_PAYMENT_ALREADY_BOUND");
        }
        BigDecimal orderAmount = jdbc.queryForObject(
                "SELECT pay_price FROM yshop_store_order WHERE order_id = ? AND uid = ? FOR UPDATE",
                BigDecimal.class, orderNo, payment.memberId());
        if (orderAmount == null || toCent(orderAmount) != payment.amountCent()) {
            throw conflict("MINIPAY_ORDER_AMOUNT_MISMATCH");
        }
        try {
            int changed = jdbc.update("""
                    UPDATE yshop_minipay_payment
                       SET payment_order_id = ?, payment_status = 'PAID', updated_at = NOW(6)
                     WHERE yshop_order_no = ? AND payment_status IN ('UNPAID', 'PROCESSING', 'PAID')
                    """, request.paymentOrderId(), orderNo);
            if (changed != 1) throw conflict("MINIPAY_ORDER_NOT_PAYABLE");
        } catch (DuplicateKeyException exception) {
            throw conflict("MINIPAY_PAYMENT_ALREADY_BOUND");
        }
        int paid = jdbc.update("""
                UPDATE yshop_store_order
                   SET paid = 1, pay_type = 'minipay', pay_time = NOW(6), update_time = NOW(6)
                 WHERE order_id = ? AND uid = ? AND paid = 0 AND pay_price = ?
                """, orderNo, payment.memberId(), orderAmount);
        if (paid == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paid = 1 FROM yshop_store_order WHERE order_id = ?", Boolean.class, orderNo))) {
            throw conflict("MINIPAY_ORDER_NOT_PAYABLE");
        }
        // 走统一支付成功流程：写消费流水（历史消费 sumMoney 统计依赖 type=3 流水）、
        // 增加购买次数与订单状态日志。事件去重（claimEvent）保证只执行一次。
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            orders.paySuccess(orderNo, "minipay");
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
        return new EventResult(true, "PAID");
    }

    @Transactional
    public EventResult paymentClosed(String orderNo, CloseRequest request) {
        PaymentRow payment = lockPayment(orderNo, request.subject());
        if (!claimEvent(request.eventId(), "payment.food-order.closed", orderNo, request)) {
            return new EventResult(true, payment.paymentStatus());
        }
        if ("PAID".equals(payment.paymentStatus())) throw conflict("MINIPAY_PAID_ORDER_CANNOT_CLOSE");
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            orders.cancelOrder(orderNo, payment.memberId());
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
        jdbc.update("UPDATE yshop_minipay_payment SET payment_status = 'CLOSED', updated_at = NOW(6) WHERE yshop_order_no = ?",
                orderNo);
        return new EventResult(true, "CLOSED");
    }

    @Transactional
    public EventResult requestCancellation(String orderNo, CancellationRequest request) {
        PaymentRow payment = lockPayment(orderNo, request.subject());
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            if (!"PAID".equals(payment.paymentStatus())) {
                orders.cancelOrder(orderNo, payment.memberId());
                jdbc.update("UPDATE yshop_minipay_payment SET payment_status = 'CLOSED', updated_at = NOW(6) WHERE yshop_order_no = ?",
                        orderNo);
                return new EventResult(true, "CLOSED");
            }
            orders.orderApplyRefund(blankToDefault(request.reason(), "MiniPay用户申请退款"), "",
                    blankToDefault(request.reason(), "MiniPay用户申请退款"), orderNo, payment.memberId());
            jdbc.update("UPDATE yshop_minipay_payment SET refund_status = 'REQUESTED', updated_at = NOW(6) WHERE yshop_order_no = ?",
                    orderNo);
            return new EventResult(true, "REQUESTED");
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
    }

    @Transactional
    public EventResult refundResult(String orderNo, RefundResultRequest request) {
        PaymentRow payment = lockPayment(orderNo, request.subject());
        if (!claimEvent(request.eventId(), "payment.refund.succeeded", orderNo, request)) {
            return new EventResult(true, payment.refundStatus());
        }
        if (payment.paymentOrderId() == null
                || !payment.paymentOrderId().equals(request.paymentOrderId())) {
            throw conflict("MINIPAY_REFUND_PAYMENT_MISMATCH");
        }
        String target = switch (request.status()) {
            case "REFUNDED" -> "REFUNDED";
            case "REJECTED" -> "REJECTED";
            default -> throw invalid("MINIPAY_REFUND_STATUS_INVALID");
        };
        jdbc.update("UPDATE yshop_minipay_payment SET refund_status = ?, updated_at = NOW(6) WHERE yshop_order_no = ?",
                target, orderNo);
        if ("REFUNDED".equals(target)) {
            jdbc.update("""
                    UPDATE yshop_store_order
                       SET refund_status = 2, refund_price = pay_price, status = -2, update_time = NOW(6)
                     WHERE order_id = ? AND uid = ? AND paid = 1 AND refund_status <> 2
                    """, orderNo, payment.memberId());
        }
        return new EventResult(true, target);
    }

    private void validateQuote(QuoteRow quote, List<QuoteItem> items) {
        StoreRow store = requireStore(quote.shopId(), true);
        if ("TAKEOUT".equals(quote.fulfillment())) {
            AddressRow address = requireAddress(quote.memberId(), quote.addressId());
            if (store.deliveryDistanceKm() <= 0
                    || haversineKm(store.longitude(), store.latitude(), address.longitude(), address.latitude())
                    > store.deliveryDistanceKm()) throw conflict("MINIPAY_DELIVERY_RANGE_CHANGED");
        }
        long subtotal = 0;
        for (QuoteItem item : items) {
            SkuRow sku = requireSku(quote.shopId(), item.skuId());
            if (sku.stock() < item.quantity()) throw conflict("MINIPAY_SKU_OUT_OF_STOCK");
            if (sku.priceCent() != item.unitPriceCent()) throw conflict("MINIPAY_QUOTE_PRICE_CHANGED");
            subtotal = Math.addExact(subtotal, Math.multiplyExact(sku.priceCent(), item.quantity()));
        }
        long delivery = "TAKEOUT".equals(quote.fulfillment()) ? store.deliveryFeeCent() : 0;
        if (subtotal != quote.subtotalCent() || delivery != quote.deliveryFeeCent()
                || Math.addExact(subtotal, delivery) != quote.amountCent()) {
            throw conflict("MINIPAY_QUOTE_CHANGED");
        }
    }

    static boolean meetsMinimumOrder(String fulfillment, long subtotalCent, long minimumOrderCent) {
        return !"TAKEOUT".equals(fulfillment) || subtotalCent >= minimumOrderCent;
    }

    private StoreRow requireStore(long shopId, boolean mustBeOpen) {
        List<StoreRow> rows = jdbc.query("""
                SELECT id, name, CAST(lng AS DECIMAL(12,8)) AS lng,
                       CAST(lat AS DECIMAL(12,8)) AS lat, distance, min_price,
                       delivery_price, status, start_time, end_time
                  FROM yshop_store_shop WHERE id = ? AND deleted = b'0'
                """, (rs, ignored) -> {
            LocalTime start = rs.getTimestamp("start_time").toLocalDateTime().toLocalTime();
            LocalTime end = rs.getTimestamp("end_time").toLocalDateTime().toLocalTime();
            LocalTime now = LocalTime.now(ZoneId.of("Asia/Shanghai"));
            boolean open = start.equals(end) || (!start.isAfter(end)
                    ? !now.isBefore(start) && !now.isAfter(end)
                    : !now.isBefore(start) || !now.isAfter(end));
            return new StoreRow(rs.getLong("id"), rs.getString("name"),
                    rs.getDouble("lng"), rs.getDouble("lat"), rs.getInt("distance"),
                    toCent(rs.getBigDecimal("min_price")), toCent(rs.getBigDecimal("delivery_price")),
                    rs.getInt("status") == 1, open);
        }, shopId);
        if (rows.isEmpty()) throw notFound("MINIPAY_STORE_NOT_FOUND");
        StoreRow store = rows.get(0);
        if (mustBeOpen && (!store.enabled() || !store.open())) throw conflict("MINIPAY_STORE_CLOSED");
        return store;
    }

    private SkuRow requireSku(long shopId, long skuId) {
        List<SkuRow> rows = jdbc.query("""
                SELECT v.id, v.product_id, v.sku, v.price, v.stock,
                       p.store_name, COALESCE(v.image, p.image) AS image
                  FROM yshop_store_product_attr_value v
                  JOIN yshop_store_product p ON p.id = v.product_id
                 WHERE v.id = ? AND p.shop_id = ? AND p.deleted = b'0' AND p.is_show = 1
                """, (rs, ignored) -> new SkuRow(rs.getLong("id"), rs.getLong("product_id"),
                rs.getString("sku"), rs.getString("store_name"), rs.getString("image"),
                toCent(rs.getBigDecimal("price")), rs.getInt("stock")), skuId, shopId);
        if (rows.isEmpty()) throw notFound("MINIPAY_SKU_NOT_FOUND");
        return rows.get(0);
    }

    private AddressRow requireAddress(long memberId, Long addressId) {
        if (addressId == null) throw invalid("MINIPAY_ADDRESS_REQUIRED");
        List<AddressRow> rows = jdbc.query("""
                SELECT id, real_name, phone, address, detail,
                       CAST(longitude AS DECIMAL(12,8)) AS longitude,
                       CAST(latitude AS DECIMAL(12,8)) AS latitude
                  FROM yshop_user_address
                 WHERE id = ? AND uid = ? AND deleted = b'0'
                """, (rs, ignored) -> new AddressRow(rs.getLong("id"), rs.getString("real_name"),
                rs.getString("phone"), rs.getString("address"), rs.getString("detail"),
                rs.getDouble("longitude"), rs.getDouble("latitude")), addressId, memberId);
        if (rows.isEmpty()) throw notFound("MINIPAY_ADDRESS_NOT_FOUND");
        AddressRow address = rows.get(0);
        coordinates(address.longitude(), address.latitude());
        return address;
    }

    private QuoteRow lockQuote(String quoteId) {
        try { UUID.fromString(quoteId); } catch (Exception exception) { throw invalid("MINIPAY_QUOTE_ID_INVALID"); }
        List<QuoteRow> rows = jdbc.query("""
                SELECT quote_id, subject, member_id, shop_id, address_id, fulfillment_type,
                       items_json, subtotal_cent, delivery_fee_cent, amount_cent, expires_at, consumed_at
                  FROM yshop_minipay_checkout_quote WHERE quote_id = ? FOR UPDATE
                """, (rs, ignored) -> new QuoteRow(rs.getString("quote_id"), rs.getString("subject"),
                rs.getLong("member_id"), rs.getLong("shop_id"), (Long) rs.getObject("address_id"),
                rs.getString("fulfillment_type"), rs.getString("items_json"), rs.getLong("subtotal_cent"),
                rs.getLong("delivery_fee_cent"), rs.getLong("amount_cent"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()), quoteId);
        if (rows.isEmpty()) throw notFound("MINIPAY_QUOTE_NOT_FOUND");
        return rows.get(0);
    }

    private PaymentRow lockPayment(String orderNo, String rawSubject) {
        String subject = subject(rawSubject).toString();
        List<PaymentRow> rows = jdbc.query("""
                SELECT yshop_order_no, subject, member_id, amount_cent, payment_order_id,
                       payment_status, refund_status
                  FROM yshop_minipay_payment
                 WHERE yshop_order_no = ? AND subject = ? FOR UPDATE
                """, (rs, ignored) -> new PaymentRow(rs.getString("yshop_order_no"), rs.getString("subject"),
                rs.getLong("member_id"), rs.getLong("amount_cent"), rs.getString("payment_order_id"),
                rs.getString("payment_status"), rs.getString("refund_status")), orderNo, subject);
        if (rows.isEmpty()) throw notFound("MINIPAY_ORDER_NOT_FOUND");
        return rows.get(0);
    }

    private OrderView findByIdempotency(String subject, String key) {
        List<OrderView> rows = queryOrders("p.subject = ? AND p.idempotency_key = ?", subject, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OrderView requireOrder(String subject, String orderRefId, boolean fullAddress) {
        List<OrderView> rows = queryOrders(
                "p.subject = ? AND (p.order_ref_id = ? OR p.yshop_order_no = ?)",
                subject, orderRefId, orderRefId);
        if (rows.isEmpty()) throw notFound("MINIPAY_ORDER_NOT_FOUND");
        OrderView value = rows.get(0);
        if (fullAddress) return value;
        return new OrderView(value.orderRefId(), value.externalOrderNo(), value.shopId(), value.shopName(),
                value.amountCent(), value.currency(), value.paymentOrderId(), value.paymentStatus(),
                value.fulfillmentStatus(), value.refundStatus(), value.fulfillmentType(),
                null, null, null, value.createdAt(), value.expiresAt(), value.items(),
                value.totalQuantity(), value.subtotalCent(), value.deliveryFeeCent(),
                value.discountCent());
    }

    private List<OrderView> queryOrders(String where, Object... args) {
        String sql = """
                SELECT p.order_ref_id, p.yshop_order_no, p.amount_cent, p.currency,
                       p.payment_order_id, p.payment_status, p.refund_status,
                       p.created_at, p.expires_at, o.shop_id, o.shop_name, o.order_type,
                       o.status, o.paid, o.deleted, o.real_name, o.user_phone, o.user_address,
                       o.total_num, o.total_price, o.freight_price, o.coupon_price,
                       o.deduction_price
                  FROM yshop_minipay_payment p
                  JOIN yshop_store_order o ON o.order_id = p.yshop_order_no
                 WHERE
                """ + where;
        List<OrderView> orders = jdbc.query(sql, (rs, ignored) -> new OrderView(
                rs.getString("order_ref_id"), rs.getString("yshop_order_no"), rs.getLong("shop_id"),
                rs.getString("shop_name"), rs.getLong("amount_cent"), rs.getString("currency"),
                rs.getString("payment_order_id"), rs.getString("payment_status"),
                fulfillmentStatus(rs.getInt("status"), rs.getBoolean("deleted")),
                rs.getString("refund_status"), "takeout".equals(rs.getString("order_type")) ? "TAKEOUT" : "PICKUP",
                rs.getString("real_name"), rs.getString("user_phone"), rs.getString("user_address"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("expires_at").toInstant().toString(), List.of(),
                rs.getInt("total_num"), toCentOrZero(rs.getBigDecimal("total_price")),
                toCentOrZero(rs.getBigDecimal("freight_price")),
                orderDiscountCent(rs.getBigDecimal("coupon_price"),
                        rs.getBigDecimal("deduction_price"))), args);
        return attachOrderItems(orders);
    }

    private List<OrderView> attachOrderItems(List<OrderView> orders) {
        if (orders.isEmpty()) return orders;
        List<String> orderNumbers = orders.stream().map(OrderView::externalOrderNo).toList();
        Map<String, List<OrderItemView>> itemsByOrder = namedJdbc.query("""
                        SELECT order_id, product_id, title, spec, image, number, price
                          FROM yshop_store_order_cart_info
                         WHERE order_id IN (:orderNumbers)
                         ORDER BY id
                        """, new MapSqlParameterSource("orderNumbers", orderNumbers), rs -> {
            Map<String, List<OrderItemView>> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                int quantity = rs.getInt("number");
                long unitPriceCent = toCentOrZero(rs.getBigDecimal("price"));
                grouped.computeIfAbsent(rs.getString("order_id"), ignored -> new ArrayList<>())
                        .add(new OrderItemView(rs.getLong("product_id"), rs.getString("title"),
                                rs.getString("spec"), rs.getString("image"), quantity,
                                unitPriceCent, Math.multiplyExact(unitPriceCent, quantity)));
            }
            return grouped;
        });
        return orders.stream().map(order -> new OrderView(
                order.orderRefId(), order.externalOrderNo(), order.shopId(), order.shopName(),
                order.amountCent(), order.currency(), order.paymentOrderId(), order.paymentStatus(),
                order.fulfillmentStatus(), order.refundStatus(), order.fulfillmentType(),
                order.recipient(), order.phone(), order.address(), order.createdAt(), order.expiresAt(),
                itemsByOrder.getOrDefault(order.externalOrderNo(), List.of()), order.totalQuantity(),
                order.subtotalCent(), order.deliveryFeeCent(), order.discountCent())).toList();
    }

    static long orderDiscountCent(BigDecimal couponPrice, BigDecimal deductionPrice) {
        return Math.addExact(toCentOrZero(couponPrice), toCentOrZero(deductionPrice));
    }

    private static long toCentOrZero(BigDecimal amount) {
        return amount == null ? 0 : toCent(amount);
    }

    private boolean claimEvent(String eventId, String type, String aggregate, Object payload) {
        try { UUID.fromString(eventId); } catch (Exception exception) { throw invalid("MINIPAY_EVENT_ID_INVALID"); }
        try {
            jdbc.update("""
                    INSERT INTO yshop_minipay_event_inbox
                      (event_id, event_type, aggregate_key, payload_hash, processed_at)
                    VALUES (?, ?, ?, ?, NOW(6))
                    """, eventId, type, aggregate, sha256(writeJson(payload)));
            return true;
        } catch (DuplicateKeyException exception) {
            String existing = jdbc.queryForObject(
                    "SELECT event_type FROM yshop_minipay_event_inbox WHERE event_id = ?",
                    String.class, eventId);
            if (!type.equals(existing)) throw conflict("MINIPAY_EVENT_ID_REUSED");
            return false;
        }
    }

    private long requireMember(String rawSubject) {
        String subject = subject(rawSubject).toString();
        Long member = identityMember(subject);
        if (member == null) throw notFound("MINIPAY_IDENTITY_NOT_FOUND");
        return member;
    }

    private Long identityMember(String subject) {
        List<Long> rows = jdbc.query("""
                SELECT member_id FROM yshop_minipay_external_identity
                 WHERE provider = 'MINIPAY' AND subject = ?
                """, (rs, ignored) -> rs.getLong(1), subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IdentityRow identity(String subject) {
        List<IdentityRow> rows = jdbc.query("""
                SELECT e.member_id, e.profile_version, u.username
                  FROM yshop_minipay_external_identity e
                  JOIN yshop_user u ON u.id = e.member_id
                   AND u.deleted = b'0' AND u.tenant_id = ?
                 WHERE e.provider = 'MINIPAY' AND e.subject = ?
                """, (rs, ignored) -> new IdentityRow(
                rs.getLong("member_id"), rs.getLong("profile_version"), rs.getString("username")),
                tenantId, subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String limitedNickname(String nickname) {
        String value = nickname == null ? "" : nickname.strip();
        if (value.isEmpty()) return "MiniPay用户";
        int end = value.offsetByCodePoints(
                0, Math.min(32, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    /**
     * 头像下载失败（来源主机不在白名单、签名 URL 已过期、网络异常等）时
     * 不阻断身份同步：保留旧头像，昵称/手机号等照常更新。
     * 否则头像永远同步不上，且每次身份解析都会失败。
     */
    private String tryCopyAvatar(String source, String subject, long profileVersion) {
        try {
            return avatars.copy(source, subject, profileVersion);
        } catch (RuntimeException exception) {
            log.warn("[minipay-food] avatar copy failed, keep old avatar. subject={} reason={}",
                    subject, exception.getMessage());
            return null;
        }
    }

    private record IdentityRow(long memberId, long profileVersion, String username) { }

    private List<QuoteItem> readItems(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored quote snapshot", exception); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize MiniPay payload", exception); }
    }

    private static UUID subject(String value) {
        try { return UUID.fromString(value); }
        catch (Exception exception) { throw invalid("MINIPAY_SUBJECT_INVALID"); }
    }

    private static String fulfillment(String value) {
        if ("TAKEOUT".equals(value) || "PICKUP".equals(value)) return value;
        throw invalid("MINIPAY_FULFILLMENT_INVALID");
    }

    private static void coordinates(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < 72 || longitude > 138 || latitude < 0.8 || latitude > 56) {
            throw invalid("MINIPAY_GCJ02_COORDINATES_INVALID");
        }
    }

    static long toCent(BigDecimal yuan) {
        if (yuan == null || yuan.signum() < 0 || yuan.stripTrailingZeros().scale() > 2) {
            throw invalid("MINIPAY_AMOUNT_PRECISION_INVALID");
        }
        try {
            long cent = yuan.movePointRight(2).longValueExact();
            if (cent > MAX_AMOUNT_CENT) throw invalid("MINIPAY_AMOUNT_OUT_OF_RANGE");
            return cent;
        } catch (ArithmeticException exception) {
            throw invalid("MINIPAY_AMOUNT_OUT_OF_RANGE");
        }
    }

    static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    private static String fulfillmentStatus(int status, boolean deleted) {
        if (deleted) return "CANCELLED";
        return switch (status) {
            case 0 -> "PLACED";
            case 1 -> "DELIVERING";
            case 2 -> "COMPLETED";
            case 3 -> "COMPLETED";
            case -1, -2 -> "CANCELLED";
            default -> "PREPARING";
        };
    }

    private static String maskPhone(String value) {
        if (value == null || value.length() < 7) return "***";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String maskName(String value) {
        return value == null || value.isBlank() ? "**" : value.substring(0, 1) + "**";
    }

    private static String maskAddress(String address, String detail) {
        String value = blankToDefault(address, "") + " " + blankToDefault(detail, "");
        return value.length() <= 12 ? value : value.substring(0, 12) + "…";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static MiniPayProblem invalid(String code) { return new MiniPayProblem(code, HttpStatus.UNPROCESSABLE_ENTITY); }
    private static MiniPayProblem conflict(String code) { return new MiniPayProblem(code, HttpStatus.CONFLICT); }
    private static MiniPayProblem notFound(String code) { return new MiniPayProblem(code, HttpStatus.NOT_FOUND); }

    private record StoreRow(long id, String name, double longitude, double latitude,
                            int deliveryDistanceKm, long minimumOrderCent, long deliveryFeeCent,
                            boolean enabled, boolean open) { }
    private record MemberRow(long memberId, String username, int status) { }
    private record SkuRow(long id, long productId, String sku, String name, String image,
                          long priceCent, int stock) { }
    private record AddressRow(long id, String recipient, String phone, String address, String detail,
                              double longitude, double latitude) { }
    private record QuoteRow(String quoteId, String subject, long memberId, long shopId, Long addressId,
                            String fulfillment, String itemsJson, long subtotalCent, long deliveryFeeCent,
                            long amountCent, Instant expiresAt, Instant consumedAt) { }
    private record PaymentRow(String orderNo, String subject, long memberId, long amountCent,
                              String paymentOrderId, String paymentStatus, String refundStatus) { }
}
