package com.minipay.commerce.application;

import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.bytesToUuid;
import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.uuidToBytes;
import static com.minipay.commerce.infrastructure.yshop.YshopModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.infrastructure.yshop.YshopGateway;
import com.minipay.commerce.infrastructure.identity.IdentityDisclosureGateway;
import com.minipay.commerce.infrastructure.identity.IdentityDisclosureGateway.Disclosure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class YshopFoodIntegrationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final YshopGateway yshop;
    private final IdentityDisclosureGateway identity;
    private final String provider;
    private final String h5Origin;

    public YshopFoodIntegrationService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            YshopGateway yshop,
            IdentityDisclosureGateway identity,
            @Value("${minipay.commerce.food-provider:sandbox}") String provider,
            @Value("${minipay.commerce.food-h5-origin:https://food.minipay.local}") String h5Origin) {
        this.jdbc = jdbc;
        this.json = json;
        this.yshop = yshop;
        this.identity = identity;
        this.provider = provider;
        this.h5Origin = h5Origin;
    }

    public BindingView binding(UUID userId) {
        BindingView stored = bindingStored(userId);
        if (!stored.active() || normalizedUsername(stored.username()) != null
                || stored.bindingId() == null) return stored;
        try {
            IdentityView identity = yshop.identity(stored.subject());
            String username = normalizedUsername(identity.username());
            if (username == null) return stored;
            jdbc.update("""
                    UPDATE food_external_binding
                       SET provider_username = ?, updated_at = UTC_TIMESTAMP(6)
                     WHERE binding_id = ? AND user_id = ? AND status = 'ACTIVE'
                    """, username, uuidToBytes(stored.bindingId()), uuidToBytes(userId));
            return bindingStored(userId);
        } catch (CommerceApplicationException exception) {
            return stored;
        }
    }

    private BindingView bindingStored(UUID userId) {
        List<BindingView> rows = jdbc.query("""
                SELECT binding_id, provider, provider_subject, provider_username, authorization_id,
                       profile_version, profile_sync_status, last_used_at, status, created_at
                  FROM food_external_binding WHERE user_id = ? AND provider = 'YSHOP'
                """, (rs, ignored) -> new BindingView(
                bytesToUuid(rs.getBytes("binding_id")), rs.getString("provider"),
                rs.getString("provider_subject"), "ACTIVE".equals(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBytes("authorization_id") == null ? null : bytesToUuid(rs.getBytes("authorization_id")),
                rs.getLong("profile_version"), rs.getString("profile_sync_status"),
                rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                rs.getString("provider_username")),
                uuidToBytes(userId));
        return rows.isEmpty() ? new BindingView(
                null, "YSHOP", userId.toString(), false, null, null, 0, "PENDING", null, null)
                : rows.get(0);
    }

    public BindingView bind(UUID userId) {
        requireYshop();
        Disclosure disclosure = identity.disclosure(userId);
        IdentityView identityView = yshop.resolveIdentity(new IdentityRequest(
                disclosure.subject().toString(), disclosure.nickname(), disclosure.phone(),
                disclosure.avatarFetchUrl(), disclosure.profileVersion()));
        return saveBinding(userId, disclosure, identityView);
    }

    public BindingView bind(UUID userId, String ignoredNickname, String ignoredAvatar) {
        return bind(userId);
    }

    @Transactional
    protected BindingView saveBinding(UUID userId, Disclosure disclosure, IdentityView identityView) {
        BindingView existing = bindingStored(userId);
        UUID bindingId = existing.bindingId() == null ? UuidV7.generate() : existing.bindingId();
        if (existing.bindingId() == null) {
            jdbc.update("""
                    INSERT INTO food_external_binding
                      (binding_id, user_id, provider, provider_subject, provider_member_id,
                       provider_username,
                       authorization_id, profile_version, profile_sync_status,
                       last_profile_synced_at, last_used_at, status, created_at, updated_at)
                    VALUES (?, ?, 'YSHOP', ?, ?, ?, ?, ?, 'SYNCED', UTC_TIMESTAMP(6),
                            UTC_TIMESTAMP(6), 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uuidToBytes(bindingId), uuidToBytes(userId), identityView.subject(),
                    Long.toString(identityView.memberId()), normalizedUsername(identityView.username()),
                    uuidToBytes(disclosure.authorizationId()),
                    disclosure.profileVersion());
        } else {
            jdbc.update("""
                    UPDATE food_external_binding
                       SET status = 'ACTIVE', provider_member_id = ?, provider_username = ?,
                           authorization_id = ?,
                           profile_version = ?, profile_sync_status = 'SYNCED',
                           last_profile_synced_at = UTC_TIMESTAMP(6), last_used_at = UTC_TIMESTAMP(6),
                           revoked_at = NULL,
                           updated_at = UTC_TIMESTAMP(6) WHERE binding_id = ?
                    """, Long.toString(identityView.memberId()), normalizedUsername(identityView.username()),
                    uuidToBytes(disclosure.authorizationId()),
                    disclosure.profileVersion(), uuidToBytes(bindingId));
        }
        return bindingStored(userId);
    }

    public EntryStatusView entryStatus(UUID userId) {
        BindingView binding = binding(userId);
        try {
            Disclosure disclosure = identity.disclosure(userId);
            if (!binding.active()) {
                return new EntryStatusView("SYNC_FAILED", false, false,
                        disclosure.grantedScopes(), disclosure.grantedScopes().contains("location.current"));
            }
            return new EntryStatusView("READY", true, false,
                    disclosure.grantedScopes(), disclosure.grantedScopes().contains("location.current"));
        } catch (CommerceApplicationException exception) {
            if ("COMMERCE_PHONE_UPGRADE_REQUIRED".equals(exception.code())) {
                return new EntryStatusView("PHONE_UPGRADE_REQUIRED", binding.active(), true,
                        Set.of("profile.basic", "profile.phone"), false);
            }
            if ("COMMERCE_FOOD_AUTHORIZATION_REQUIRED".equals(exception.code())) {
                return new EntryStatusView("NOT_AUTHORIZED", binding.active(), false, Set.of(), false);
            }
            return new EntryStatusView("SYNC_FAILED", binding.active(), false, Set.of(), false);
        }
    }

    public void unbind(UUID userId) {
        List<String> subjects = jdbc.query("""
                SELECT provider_subject FROM food_external_binding
                 WHERE user_id = ? AND provider = 'YSHOP' AND status = 'ACTIVE'
                """, (rs, ignored) -> rs.getString(1), uuidToBytes(userId));
        if (!subjects.isEmpty()) {
            yshop.revokeSessions(subjects.get(0));
            yshop.detachIdentity(subjects.get(0));
        }
        jdbc.update("""
                UPDATE food_external_binding
                   SET status = 'REVOKED', revoked_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND provider = 'YSHOP' AND status = 'ACTIVE'
                """, uuidToBytes(userId));
        jdbc.update("DELETE FROM food_handoff_code WHERE user_id = ?", uuidToBytes(userId));
    }

    public void syncProfileIfBound(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM food_external_binding
                 WHERE user_id = ? AND provider = 'YSHOP' AND status = 'ACTIVE'
                """, Integer.class, uuidToBytes(userId));
        if (count != null && count > 0) bind(userId);
    }

    @Transactional
    public HandoffView issueHandoff(UUID userId, String deviceId) {
        requireBinding(userId);
        bind(userId);
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128) {
            throw problem("COMMERCE_DEVICE_ID_REQUIRED", "设备标识不能为空");
        }
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] proofRaw = new byte[32];
        new java.security.SecureRandom().nextBytes(proofRaw);
        String deviceProof = Base64.getUrlEncoder().withoutPadding().encodeToString(proofRaw);
        Instant expires = Instant.now().plusSeconds(60);
        jdbc.update("""
                INSERT INTO food_handoff_code
                  (code_digest, user_id, device_id, device_proof_digest, allowed_origin, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, sha256(code), uuidToBytes(userId), deviceId, sha256(deviceProof), h5Origin,
                Timestamp.from(expires));
        return new HandoffView(code, deviceProof, h5Origin, expires);
    }

    @Transactional
    public HandoffIdentity consumeHandoff(String code, String deviceProof, String origin) {
        List<HandoffRow> rows = jdbc.query("""
                SELECT h.user_id, h.device_proof_digest, h.allowed_origin, h.expires_at, h.consumed_at,
                       b.provider_subject
                  FROM food_handoff_code h
                  JOIN food_external_binding b ON b.user_id = h.user_id
                     AND b.provider = 'YSHOP' AND b.status = 'ACTIVE'
                WHERE h.code_digest = ? FOR UPDATE
                """, (rs, ignored) -> new HandoffRow(bytesToUuid(rs.getBytes("user_id")),
                rs.getBytes("device_proof_digest"), rs.getString("allowed_origin"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getString("provider_subject")), sha256(code));
        if (rows.isEmpty()) throw problem("COMMERCE_HANDOFF_INVALID", "免登码无效");
        HandoffRow row = rows.get(0);
        if (row.consumedAt() != null || !row.expiresAt().isAfter(Instant.now())
                || deviceProof == null
                || !MessageDigest.isEqual(row.deviceProofDigest(), sha256(deviceProof))
                || !row.origin().equals(origin)) {
            throw problem("COMMERCE_HANDOFF_INVALID", "免登码已过期或上下文不匹配");
        }
        int changed = jdbc.update("""
                UPDATE food_handoff_code SET consumed_at = UTC_TIMESTAMP(6)
                 WHERE code_digest = ? AND consumed_at IS NULL AND expires_at > UTC_TIMESTAMP(6)
                """, sha256(code));
        if (changed != 1) throw problem("COMMERCE_HANDOFF_REPLAYED", "免登码已使用");
        return new HandoffIdentity(row.subject());
    }

    public LocationView createLocation(
            UUID userId, double longitude, double latitude, Double accuracyMeters,
            Instant capturedAt, String source) {
        Disclosure disclosure = identity.disclosure(userId);
        if (!disclosure.grantedScopes().contains("location.current")) {
            throw problem("COMMERCE_LOCATION_SCOPE_REQUIRED", "请先授权使用当前位置");
        }
        requireBinding(userId);
        coordinates(longitude, latitude);
        if (capturedAt == null || capturedAt.isBefore(Instant.now().minus(10, ChronoUnit.MINUTES))
                || capturedAt.isAfter(Instant.now().plusSeconds(30))) {
            throw problem("COMMERCE_LOCATION_STALE", "定位信息已过期");
        }
        UUID id = UuidV7.generate();
        Instant expires = Instant.now().plus(5, ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO food_location_context
                  (location_context_id, user_id, longitude, latitude, accuracy_meters,
                   source, captured_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, uuidToBytes(id), uuidToBytes(userId), longitude, latitude, accuracyMeters,
                source == null ? "GPS" : source, Timestamp.from(capturedAt), Timestamp.from(expires));
        return new LocationView(id, expires);
    }

    public ResolvedLocationView resolveLocation(UUID locationContextId, String subject) {
        UUID userId;
        try { userId = UUID.fromString(subject); }
        catch (RuntimeException exception) {
            throw problem("COMMERCE_LOCATION_SUBJECT_INVALID", "位置凭证主体无效");
        }
        requireBinding(userId);
        Disclosure disclosure = identity.disclosure(userId);
        if (!disclosure.grantedScopes().contains("location.current")) {
            throw problem("COMMERCE_LOCATION_SCOPE_REQUIRED", "当前位置授权已失效");
        }
        List<ResolvedLocationView> rows = jdbc.query("""
                SELECT longitude, latitude, accuracy_meters, captured_at, expires_at
                  FROM food_location_context
                 WHERE location_context_id = ? AND user_id = ? AND expires_at > UTC_TIMESTAMP(6)
                """, (rs, ignored) -> new ResolvedLocationView(
                rs.getDouble("longitude"), rs.getDouble("latitude"),
                rs.getObject("accuracy_meters") == null ? null : rs.getDouble("accuracy_meters"),
                rs.getTimestamp("captured_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                uuidToBytes(locationContextId), uuidToBytes(userId));
        if (rows.isEmpty()) {
            throw problem("COMMERCE_LOCATION_CONTEXT_EXPIRED", "位置凭证不存在或已过期");
        }
        return rows.get(0);
    }

    public List<StoreView> stores(
            UUID userId, UUID locationContextId, UUID addressRefId, String fulfillment) {
        requireBinding(userId);
        if ((locationContextId == null) == (addressRefId == null)) {
            throw problem("COMMERCE_FOOD_LOCATION_REQUIRED",
                    "定位上下文和保存地址必须且只能提供一个");
        }
        double longitude;
        double latitude;
        if (locationContextId != null) {
            LocationRow location = location(userId, locationContextId);
            longitude = location.longitude();
            latitude = location.latitude();
        } else {
            long addressId = Long.parseLong(externalId("ADDRESS", addressRefId));
            Address address = yshop.addresses(userId.toString()).stream()
                    .filter(item -> item.addressId() == addressId)
                    .findFirst()
                    .orElseThrow(() -> problem("COMMERCE_ADDRESS_NOT_FOUND", "配送地址不存在"));
            coordinates(address.longitude(), address.latitude());
            longitude = address.longitude();
            latitude = address.latitude();
        }
        return yshop.stores(longitude, latitude, fulfillment, 10).stream()
                .map(store -> new StoreView(resourceRef("STORE", Long.toString(store.shopId())),
                        store.name(), store.image(), store.address(), store.distanceMeters(), store.open(),
                        store.deliverable(), store.minimumOrderCent(), store.deliveryFeeCent())).toList();
    }

    public List<ProductView> menu(UUID userId, UUID storeRefId) {
        requireBinding(userId);
        long shopId = Long.parseLong(externalId("STORE", storeRefId));
        return yshop.menu(shopId).stream().map(product -> new ProductView(
                resourceRef("PRODUCT", Long.toString(product.productId())), product.name(),
                product.description(), product.image(), product.skus().stream().map(sku -> new SkuView(
                resourceRef("SKU", Long.toString(sku.skuId())), sku.sku(), sku.priceCent(),
                sku.stock(), sku.available(), sku.image())).toList())).toList();
    }

    public List<AddressView> addresses(UUID userId) {
        requireBinding(userId);
        return yshop.addresses(userId.toString()).stream().map(address -> new AddressView(
                resourceRef("ADDRESS", Long.toString(address.addressId())), address.label(),
                address.defaultAddress(), address.maskedRecipient(), address.maskedPhone())).toList();
    }

    @Transactional
    public CartView updateCart(
            UUID userId, UUID storeRefId, UUID skuRefId, int quantity, Long expectedVersion) {
        requireBinding(userId);
        if (quantity < 0 || quantity > 99) throw problem("COMMERCE_QUANTITY_INVALID", "商品数量无效");
        // Remote validation also prevents a SKU reference from being paired with a different store.
        boolean valid = menu(userId, storeRefId).stream().flatMap(p -> p.skus().stream())
                .anyMatch(sku -> sku.skuRefId().equals(skuRefId) && sku.available()
                        && sku.stock() >= quantity);
        if (!valid && quantity > 0) throw problem("COMMERCE_SKU_UNAVAILABLE", "商品已售罄或不属于该门店");
        CartHeader cart = lockOrCreateCart(userId, storeRefId);
        if (expectedVersion != null && expectedVersion != cart.version()) {
            throw problem("COMMERCE_CART_VERSION_CONFLICT", "购物车已更新");
        }
        if (quantity == 0) {
            jdbc.update("DELETE FROM food_ai_cart_item WHERE cart_id = ? AND sku_ref_id = ?",
                    uuidToBytes(cart.cartId()), uuidToBytes(skuRefId));
        } else {
            jdbc.update("""
                    INSERT INTO food_ai_cart_item
                      (cart_item_id, cart_id, sku_ref_id, quantity, created_at, updated_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), updated_at = UTC_TIMESTAMP(6)
                    """, uuidToBytes(UuidV7.generate()), uuidToBytes(cart.cartId()),
                    uuidToBytes(skuRefId), quantity);
        }
        int changed = jdbc.update("""
                UPDATE food_ai_cart SET version = version + 1, updated_at = UTC_TIMESTAMP(6)
                 WHERE cart_id = ? AND version = ?
                """, uuidToBytes(cart.cartId()), cart.version());
        if (changed != 1) throw problem("COMMERCE_CART_VERSION_CONFLICT", "购物车已更新");
        return cart(userId, storeRefId);
    }

    public CartView cart(UUID userId, UUID storeRefId) {
        requireBinding(userId);
        List<CartHeader> headers = jdbc.query("""
                SELECT cart_id, version FROM food_ai_cart WHERE user_id = ? AND store_ref_id = ?
                """, (rs, ignored) -> new CartHeader(bytesToUuid(rs.getBytes("cart_id")),
                rs.getLong("version")), uuidToBytes(userId), uuidToBytes(storeRefId));
        if (headers.isEmpty()) return new CartView(null, storeRefId, 0, List.of());
        CartHeader header = headers.get(0);
        List<CartItemView> items = jdbc.query("""
                SELECT sku_ref_id, quantity FROM food_ai_cart_item
                 WHERE cart_id = ? ORDER BY created_at
                """, (rs, ignored) -> new CartItemView(bytesToUuid(rs.getBytes("sku_ref_id")),
                rs.getInt("quantity")), uuidToBytes(header.cartId()));
        return new CartView(header.cartId(), storeRefId, header.version(), items);
    }

    @Transactional
    public QuoteView prepareQuote(
            UUID userId, UUID storeRefId, UUID addressRefId, String fulfillment) {
        requireBinding(userId);
        CartView cart = cart(userId, storeRefId);
        if (cart.items().isEmpty()) throw problem("COMMERCE_CART_EMPTY", "购物车为空");
        long shopId = Long.parseLong(externalId("STORE", storeRefId));
        Long addressId = addressRefId == null ? null
                : Long.parseLong(externalId("ADDRESS", addressRefId));
        List<QuoteItemRequest> items = cart.items().stream().map(item -> new QuoteItemRequest(
                Long.parseLong(externalId("SKU", item.skuRefId())), item.quantity())).toList();
        Quote quote = yshop.quote(new QuoteRequest(userId.toString(), shopId, addressId,
                fulfillment, items));
        UUID quoteRef = UuidV7.generate();
        try {
            jdbc.update("""
                    INSERT INTO food_provider_quote_ref
                      (quote_ref_id, user_id, provider, external_quote_id, store_ref_id,
                       amount_cent, currency, expires_at, created_at)
                    VALUES (?, ?, 'YSHOP', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(quoteRef), uuidToBytes(userId), quote.quoteId(),
                    uuidToBytes(storeRefId), quote.amountCent(), quote.currency(),
                    Timestamp.from(Instant.parse(quote.expiresAt())));
        } catch (DuplicateKeyException exception) {
            quoteRef = jdbc.queryForObject("""
                    SELECT quote_ref_id FROM food_provider_quote_ref
                     WHERE provider = 'YSHOP' AND external_quote_id = ?
                    """, (rs, ignored) -> bytesToUuid(rs.getBytes(1)), quote.quoteId());
        }
        return new QuoteView(quoteRef, storeRefId, quote.shopName(), quote.fulfillmentType(),
                quote.items().stream().map(item -> new QuotedItemView(
                        resourceRef("SKU", Long.toString(item.skuId())), item.name(), item.sku(),
                        item.quantity(), item.unitPriceCent(), item.lineAmountCent())).toList(),
                quote.subtotalCent(), quote.deliveryFeeCent(), quote.amountCent(), quote.currency(),
                Instant.parse(quote.expiresAt()));
    }

    @Transactional
    public OrderView createOrder(UUID userId, UUID quoteRefId, String idempotencyKey, String remark) {
        requireBinding(userId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw problem("COMMERCE_IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空");
        }
        OrderView existing = localOrderByIdempotency(userId, idempotencyKey);
        if (existing != null) return existing;
        QuoteRef quote = quote(userId, quoteRefId);
        if (!quote.expiresAt().isAfter(Instant.now())) throw problem("COMMERCE_QUOTE_EXPIRED", "报价已过期");
        Order external = yshop.createOrder(new CreateOrderRequest(
                userId.toString(), quote.externalQuoteId(), remark), idempotencyKey);
        if (external.amountCent() != quote.amountCent() || !external.currency().equals(quote.currency())) {
            throw problem("COMMERCE_PROVIDER_AMOUNT_MISMATCH", "外卖订单金额与报价不一致");
        }
        UUID orderRef = UuidV7.generate();
        Instant expires = Instant.parse(external.expiresAt());
        jdbc.update("""
                INSERT INTO food_external_order_ref
                  (order_ref_id, user_id, provider, external_order_no, provider_order_ref,
                   quote_ref_id, idempotency_key, amount_cent, currency, payment_status,
                   fulfillment_status, refund_status, expires_at, created_at, updated_at)
                VALUES (?, ?, 'YSHOP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uuidToBytes(orderRef), uuidToBytes(userId), external.externalOrderNo(),
                external.orderRefId(), uuidToBytes(quoteRefId), idempotencyKey, external.amountCent(),
                external.currency(), external.paymentStatus(), external.fulfillmentStatus(),
                external.refundStatus(), Timestamp.from(expires));
        appendFoodOrderCreated(orderRef, external.externalOrderNo(), userId,
                external.amountCent(), external.currency());
        return localOrder(userId, orderRef);
    }

    @Transactional
    public OrderView preparePaymentForExternalOrder(
            UUID userId, String externalOrderNo, String idempotencyKey) {
        requireBinding(userId);
        if (externalOrderNo == null || externalOrderNo.isBlank() || externalOrderNo.length() > 64) {
            throw problem("COMMERCE_EXTERNAL_ORDER_NO_INVALID", "外卖订单号无效");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw problem("COMMERCE_IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空");
        }
        OrderView existing = localOrderByExternalNo(userId, externalOrderNo.strip());
        if (existing != null) {
            int promoted = jdbc.update("""
                    UPDATE food_external_order_ref
                       SET idempotency_key = ?, updated_at = UTC_TIMESTAMP(6)
                     WHERE user_id = ? AND order_ref_id = ?
                       AND idempotency_key LIKE 'discovered:%'
                    """, idempotencyKey, uuidToBytes(userId), uuidToBytes(existing.orderRefId()));
            if (promoted == 1) {
                Order discovered = yshop.order(externalOrderNo.strip(), userId.toString());
                validatePayableExternalOrder(discovered);
                appendFoodOrderCreated(existing.orderRefId(), discovered.externalOrderNo(), userId,
                        discovered.amountCent(), discovered.currency());
            }
            return order(userId, existing.orderRefId());
        }

        Order external = yshop.order(externalOrderNo.strip(), userId.toString());
        validatePayableExternalOrder(external);
        Instant expiresAt = Instant.parse(external.expiresAt());

        UUID orderRef = UuidV7.generate();
        try {
            jdbc.update("""
                    INSERT INTO food_external_order_ref
                      (order_ref_id, user_id, provider, external_order_no, provider_order_ref,
                       quote_ref_id, idempotency_key, amount_cent, currency, payment_status,
                       fulfillment_status, refund_status, expires_at, created_at, updated_at)
                    VALUES (?, ?, 'YSHOP', ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?,
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uuidToBytes(orderRef), uuidToBytes(userId), external.externalOrderNo(),
                    external.orderRefId(), idempotencyKey, external.amountCent(), external.currency(),
                    external.paymentStatus(), external.fulfillmentStatus(), external.refundStatus(),
                    Timestamp.from(expiresAt));
            appendFoodOrderCreated(orderRef, external.externalOrderNo(), userId,
                    external.amountCent(), external.currency());
        } catch (DuplicateKeyException exception) {
            OrderView concurrent = localOrderByExternalNo(userId, external.externalOrderNo());
            if (concurrent == null) {
                throw problem("COMMERCE_IDEMPOTENCY_CONFLICT", "幂等键已被其他订单使用");
            }
            orderRef = concurrent.orderRefId();
        }
        return order(userId, orderRef);
    }

    public List<OrderView> listOrders(UUID userId, int page, int size) {
        requireBinding(userId);
        List<Order> externalOrders = yshop.orders(userId.toString(), Math.max(page, 0),
                Math.min(Math.max(size, 1), 100));
        return externalOrders.stream().map(external -> {
            OrderView local = discoverOrderReference(userId, external);
            return mergeOrderPresentation(local, external);
        }).toList();
    }

    public OrderView order(UUID userId, UUID orderRefId) {
        OrderRef row = orderRef(userId, orderRefId);
        Order external = yshop.order(row.providerOrderRef(), userId.toString());
        refreshLocalOrder(userId, external);
        OrderView local = localOrder(userId, orderRefId);
        return mergeOrderPresentation(local, external);
    }

    private static OrderView mergeOrderPresentation(OrderView local, Order external) {
        List<OrderItemView> items = external.items() == null ? List.of() : external.items().stream()
                .map(item -> new OrderItemView(item.productId(), item.name(), item.sku(), item.image(),
                        item.quantity(), item.unitPriceCent(), item.lineAmountCent()))
                .toList();
        return new OrderView(local.orderRefId(), local.externalOrderNo(), external.amountCent(), external.currency(),
                local.paymentOrderId(), local.paymentStatus(), local.fulfillmentStatus(), local.refundStatus(),
                Instant.parse(external.expiresAt()), Instant.parse(external.createdAt()),
                external.shopName(), external.fulfillmentType(),
                external.recipient(), external.phone(), external.address(), items,
                external.totalQuantity(), external.subtotalCent(), external.deliveryFeeCent(),
                external.discountCent());
    }

    private OrderView discoverOrderReference(UUID userId, Order external) {
        refreshLocalOrder(userId, external);
        OrderView existing = localOrderByExternalNo(userId, external.externalOrderNo());
        if (existing != null) return existing;
        UUID orderRef = UuidV7.generate();
        try {
            jdbc.update("""
                    INSERT INTO food_external_order_ref
                      (order_ref_id, user_id, provider, external_order_no, provider_order_ref,
                       quote_ref_id, idempotency_key, amount_cent, currency, payment_status,
                       fulfillment_status, refund_status, expires_at, created_at, updated_at)
                    VALUES (?, ?, 'YSHOP', ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?,
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uuidToBytes(orderRef), uuidToBytes(userId), external.externalOrderNo(),
                    external.orderRefId(), "discovered:" + external.externalOrderNo(),
                    external.amountCent(), external.currency(), external.paymentStatus(),
                    external.fulfillmentStatus(), external.refundStatus(),
                    Timestamp.from(Instant.parse(external.expiresAt())));
        } catch (DuplicateKeyException exception) {
            OrderView concurrent = localOrderByExternalNo(userId, external.externalOrderNo());
            if (concurrent != null) return concurrent;
            throw problem("COMMERCE_IDEMPOTENCY_CONFLICT", "订单引用创建冲突");
        }
        return localOrder(userId, orderRef);
    }

    private static void validatePayableExternalOrder(Order external) {
        if (!"UNPAID".equals(external.paymentStatus()) && !"FAILED".equals(external.paymentStatus())) {
            throw problem("COMMERCE_FOOD_ORDER_NOT_PAYABLE", "外卖订单当前不可付款");
        }
        Instant expiresAt = Instant.parse(external.expiresAt());
        if (!expiresAt.isAfter(Instant.now())) {
            throw problem("COMMERCE_FOOD_ORDER_EXPIRED", "外卖订单已超过支付期限");
        }
        if (external.amountCent() <= 0 || !"CNY".equals(external.currency())) {
            throw problem("COMMERCE_PROVIDER_AMOUNT_INVALID", "外卖订单金额或币种无效");
        }
    }

    public OrderView requestCancellation(UUID userId, UUID orderRefId, String reason) {
        OrderRef row = orderRef(userId, orderRefId);
        yshop.requestCancellation(row.externalOrderNo(), new CancellationRequest(
                userId.toString(), reason == null || reason.isBlank() ? "用户取消订单" : reason.strip()));
        Order external = yshop.order(row.providerOrderRef(), userId.toString());
        refreshLocalOrder(userId, external);
        return order(userId, orderRefId);
    }

    private void appendFoodOrderCreated(
            UUID orderId, String externalOrderNo, UUID userId, long amountCent, String currency) {
        UUID eventId = UuidV7.generate();
        String payload = writeJson(Map.of(
                "provider", "YSHOP", "orderId", orderId,
                "externalOrderNo", externalOrderNo, "userId", userId,
                "amountCent", amountCent, "currency", currency));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO outbox_event
                  (event_id, event_type, aggregate_type, aggregate_id, occurred_at, trace_id,
                   payload_version, payload, status, attempts, next_attempt_at,
                   lease_owner, lease_until, last_error, published_at, created_at)
                VALUES (?, 'commerce.food-order.created', 'FOOD_ORDER', ?, ?, ?, 2, ?,
                        'PENDING', 0, ?, NULL, NULL, NULL, NULL, ?)
                """, uuidToBytes(eventId), uuidToBytes(orderId), Timestamp.from(now), eventId.toString(),
                payload, Timestamp.from(now), Timestamp.from(now));
    }

    private void refreshLocalOrder(UUID userId, Order external) {
        jdbc.update("""
                UPDATE food_external_order_ref
                   SET payment_order_id = COALESCE(?, payment_order_id), payment_status = ?,
                       fulfillment_status = ?, refund_status = ?, updated_at = UTC_TIMESTAMP(6)
                 WHERE user_id = ? AND provider = 'YSHOP' AND external_order_no = ?
                """, external.paymentOrderId() == null ? null : uuidToBytes(UUID.fromString(external.paymentOrderId())),
                external.paymentStatus(), external.fulfillmentStatus(), external.refundStatus(),
                uuidToBytes(userId), external.externalOrderNo());
    }

    private CartHeader lockOrCreateCart(UUID userId, UUID storeRefId) {
        List<CartHeader> rows = jdbc.query("""
                SELECT cart_id, version FROM food_ai_cart
                 WHERE user_id = ? AND store_ref_id = ? FOR UPDATE
                """, (rs, ignored) -> new CartHeader(bytesToUuid(rs.getBytes("cart_id")),
                rs.getLong("version")), uuidToBytes(userId), uuidToBytes(storeRefId));
        if (!rows.isEmpty()) return rows.get(0);
        UUID id = UuidV7.generate();
        try {
            jdbc.update("""
                    INSERT INTO food_ai_cart
                      (cart_id, user_id, store_ref_id, version, created_at, updated_at)
                    VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uuidToBytes(id), uuidToBytes(userId), uuidToBytes(storeRefId));
            return new CartHeader(id, 0);
        } catch (DuplicateKeyException exception) {
            return lockOrCreateCart(userId, storeRefId);
        }
    }

    private UUID resourceRef(String type, String externalId) {
        List<UUID> existing = jdbc.query("""
                SELECT resource_ref_id FROM food_provider_resource_ref
                 WHERE provider = 'YSHOP' AND resource_type = ? AND external_id = ?
                """, (rs, ignored) -> bytesToUuid(rs.getBytes(1)), type, externalId);
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = UuidV7.generate();
        try {
            jdbc.update("""
                    INSERT INTO food_provider_resource_ref
                      (resource_ref_id, provider, resource_type, external_id, created_at)
                    VALUES (?, 'YSHOP', ?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(id), type, externalId);
            return id;
        } catch (DuplicateKeyException exception) {
            return resourceRef(type, externalId);
        }
    }

    private String externalId(String type, UUID ref) {
        List<String> values = jdbc.query("""
                SELECT external_id FROM food_provider_resource_ref
                 WHERE resource_ref_id = ? AND provider = 'YSHOP' AND resource_type = ?
                """, (rs, ignored) -> rs.getString(1), uuidToBytes(ref), type);
        if (values.isEmpty()) throw problem("COMMERCE_RESOURCE_NOT_FOUND", "外卖资源不存在");
        return values.get(0);
    }

    private QuoteRef quote(UUID userId, UUID quoteRefId) {
        List<QuoteRef> rows = jdbc.query("""
                SELECT external_quote_id, amount_cent, currency, expires_at
                  FROM food_provider_quote_ref WHERE quote_ref_id = ? AND user_id = ?
                """, (rs, ignored) -> new QuoteRef(rs.getString("external_quote_id"),
                rs.getLong("amount_cent"), rs.getString("currency"),
                rs.getTimestamp("expires_at").toInstant()), uuidToBytes(quoteRefId), uuidToBytes(userId));
        if (rows.isEmpty()) throw problem("COMMERCE_QUOTE_NOT_FOUND", "报价不存在");
        return rows.get(0);
    }

    private LocationRow location(UUID userId, UUID locationId) {
        List<LocationRow> rows = jdbc.query("""
                SELECT longitude, latitude FROM food_location_context
                 WHERE location_context_id = ? AND user_id = ? AND expires_at > UTC_TIMESTAMP(6)
                """, (rs, ignored) -> new LocationRow(rs.getDouble("longitude"), rs.getDouble("latitude")),
                uuidToBytes(locationId), uuidToBytes(userId));
        if (rows.isEmpty()) throw problem("COMMERCE_LOCATION_CONTEXT_EXPIRED", "定位上下文不存在或已过期");
        return rows.get(0);
    }

    private BindingView requireBinding(UUID userId) {
        BindingView result = binding(userId);
        if (!result.active()) throw problem("COMMERCE_FOOD_BINDING_REQUIRED", "请先授权外卖服务");
        return result;
    }

    private OrderRef orderRef(UUID userId, UUID id) {
        List<OrderRef> rows = jdbc.query("""
                SELECT provider_order_ref, external_order_no FROM food_external_order_ref
                 WHERE order_ref_id = ? AND user_id = ? AND provider = 'YSHOP'
                """, (rs, ignored) -> new OrderRef(rs.getString("provider_order_ref"),
                rs.getString("external_order_no")), uuidToBytes(id), uuidToBytes(userId));
        if (rows.isEmpty()) throw problem("COMMERCE_ORDER_NOT_FOUND", "订单不存在");
        return rows.get(0);
    }

    private OrderView localOrderByIdempotency(UUID userId, String key) {
        List<OrderView> rows = jdbc.query("""
                SELECT order_ref_id, external_order_no, amount_cent, currency, payment_order_id,
                       payment_status, fulfillment_status, refund_status, expires_at, created_at
                  FROM food_external_order_ref WHERE user_id = ? AND idempotency_key = ?
                """, (rs, ignored) -> mapOrder(rs), uuidToBytes(userId), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OrderView localOrderByExternalNo(UUID userId, String externalOrderNo) {
        List<OrderView> rows = jdbc.query("""
                SELECT order_ref_id, external_order_no, amount_cent, currency, payment_order_id,
                       payment_status, fulfillment_status, refund_status, expires_at, created_at
                  FROM food_external_order_ref
                 WHERE user_id = ? AND provider = 'YSHOP' AND external_order_no = ?
                """, (rs, ignored) -> mapOrder(rs), uuidToBytes(userId), externalOrderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OrderView localOrder(UUID userId, UUID orderId) {
        List<OrderView> rows = jdbc.query("""
                SELECT order_ref_id, external_order_no, amount_cent, currency, payment_order_id,
                       payment_status, fulfillment_status, refund_status, expires_at, created_at
                  FROM food_external_order_ref WHERE user_id = ? AND order_ref_id = ?
                """, (rs, ignored) -> mapOrder(rs), uuidToBytes(userId), uuidToBytes(orderId));
        if (rows.isEmpty()) throw problem("COMMERCE_ORDER_NOT_FOUND", "订单不存在");
        return rows.get(0);
    }

    private static OrderView mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        byte[] paymentId = rs.getBytes("payment_order_id");
        return new OrderView(bytesToUuid(rs.getBytes("order_ref_id")), rs.getString("external_order_no"),
                rs.getLong("amount_cent"), rs.getString("currency"),
                paymentId == null ? null : bytesToUuid(paymentId), rs.getString("payment_status"),
                rs.getString("fulfillment_status"), rs.getString("refund_status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                null, null, null, null, null, List.of(), 0, 0, 0, 0);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private void requireYshop() {
        if (!"yshop".equalsIgnoreCase(provider)) {
            throw problem("COMMERCE_YSHOP_PROVIDER_DISABLED", "正式外卖 Provider 未启用");
        }
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void coordinates(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < 72 || longitude > 138 || latitude < 0.8 || latitude > 56) {
            throw problem("COMMERCE_GCJ02_COORDINATES_INVALID", "定位坐标无效");
        }
    }

    private static CommerceApplicationException problem(String code, String message) {
        return new CommerceApplicationException(code, message);
    }

    private static String normalizedUsername(String username) {
        if (username == null || username.isBlank()) return null;
        String value = username.strip();
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    public record BindingView(UUID bindingId, String provider, String subject,
                              boolean active, Instant createdAt, UUID authorizationId,
                              long profileVersion, String profileSyncStatus, Instant lastUsedAt,
                              String username) { }
    public record EntryStatusView(String state, boolean bindingActive,
                                  boolean profileUpgradeRequired, Set<String> grantedScopes,
                                  boolean locationAllowed) { }
    public record HandoffView(String code, String deviceProof, String origin, Instant expiresAt) { }
    public record HandoffIdentity(String subject) { }
    public record LocationView(UUID locationContextId, Instant expiresAt) { }
    public record ResolvedLocationView(double longitude, double latitude, Double accuracyMeters,
                                       Instant capturedAt, Instant expiresAt) { }
    public record StoreView(UUID storeRefId, String name, String image, String address,
                            long distanceMeters, boolean open, boolean deliverable,
                            long minimumOrderCent, long deliveryFeeCent) { }
    public record SkuView(UUID skuRefId, String name, long priceCent, int stock,
                          boolean available, String image) { }
    public record ProductView(UUID productRefId, String name, String description,
                              String image, List<SkuView> skus) { }
    public record AddressView(UUID addressRefId, String label, boolean defaultAddress,
                              String maskedRecipient, String maskedPhone) { }
    public record CartItemView(UUID skuRefId, int quantity) { }
    public record CartView(UUID cartId, UUID storeRefId, long version, List<CartItemView> items) { }
    public record QuotedItemView(UUID skuRefId, String name, String sku, int quantity,
                                 long unitPriceCent, long lineAmountCent) { }
    public record QuoteView(UUID quoteId, UUID storeRefId, String storeName, String fulfillmentType,
                            List<QuotedItemView> items, long subtotalCent, long deliveryFeeCent,
                            long amountCent, String currency, Instant expiresAt) { }
    public record OrderItemView(long productId, String name, String sku, String image,
                                int quantity, long unitPriceCent, long lineAmountCent) { }
    public record OrderView(UUID orderRefId, String externalOrderNo, long amountCent, String currency,
                            UUID paymentOrderId, String paymentStatus, String fulfillmentStatus,
                            String refundStatus, Instant expiresAt, Instant createdAt,
                            String storeName, String fulfillmentType, String recipient,
                            String phone, String address, List<OrderItemView> items,
                            int totalQuantity, long subtotalCent, long deliveryFeeCent,
                            long discountCent) { }
    private record HandoffRow(UUID userId, byte[] deviceProofDigest, String origin, Instant expiresAt,
                              Instant consumedAt, String subject) { }
    private record LocationRow(double longitude, double latitude) { }
    private record CartHeader(UUID cartId, long version) { }
    private record QuoteRef(String externalQuoteId, long amountCent, String currency, Instant expiresAt) { }
    private record OrderRef(String providerOrderRef, String externalOrderNo) { }
}
