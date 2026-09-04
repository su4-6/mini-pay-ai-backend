package com.minipay.commerce.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceApplicationException;
import com.minipay.commerce.application.UuidV7;
import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.CartSnapshot;
import com.minipay.commerce.domain.model.CatalogView;
import com.minipay.commerce.domain.model.CheckoutQuote;
import com.minipay.commerce.domain.model.FoodOrder;
import com.minipay.commerce.domain.model.DeliveryAddress;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import com.minipay.commerce.domain.model.PaymentStatus;
import com.minipay.commerce.domain.model.RefundStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommerceRepository implements CommerceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCommerceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CatalogView.Merchant> searchMerchants(
            String zoneCode,
            String categoryCode,
            Long maxDeliveryFeeCent,
            Integer maxDeliveryMinutes,
            int limit) {
        return jdbc.query("""
                SELECT m.merchant_id, m.name, m.category_code, m.minimum_order_cent,
                       m.delivery_fee_cent, m.estimated_delivery_minutes
                FROM merchant m
                JOIN merchant_delivery_zone z ON z.merchant_id = m.merchant_id
                WHERE m.status = 'OPEN' AND z.zone_code = ?
                  AND (? IS NULL OR m.category_code = ?)
                  AND (? IS NULL OR m.delivery_fee_cent <= ?)
                  AND (? IS NULL OR m.estimated_delivery_minutes <= ?)
                ORDER BY m.estimated_delivery_minutes, m.merchant_id
                LIMIT ?
                """, (rs, row) -> new CatalogView.Merchant(
                        bytesToUuid(rs.getBytes("merchant_id")),
                        rs.getString("name"),
                        rs.getString("category_code"),
                        rs.getLong("minimum_order_cent"),
                        rs.getLong("delivery_fee_cent"),
                        rs.getInt("estimated_delivery_minutes")),
                zoneCode, categoryCode, categoryCode,
                maxDeliveryFeeCent, maxDeliveryFeeCent,
                maxDeliveryMinutes, maxDeliveryMinutes, limit);
    }

    @Override
    public List<CatalogView.MenuItem> menu(UUID merchantId) {
        List<MenuRow> rows = jdbc.query("""
                SELECT i.item_id, i.name AS item_name, i.description, i.taste_tags,
                       i.allergen_tags, s.sku_id, s.name AS sku_name, s.price_cent,
                       inv.available_quantity, s.version
                FROM menu_item i
                JOIN menu_category c ON c.category_id = i.category_id AND c.status = 'ACTIVE'
                JOIN menu_sku s ON s.item_id = i.item_id AND s.status = 'ACTIVE'
                JOIN sku_inventory inv ON inv.sku_id = s.sku_id
                JOIN merchant m ON m.merchant_id = i.merchant_id AND m.status = 'OPEN'
                WHERE i.merchant_id = ? AND i.status = 'ACTIVE'
                ORDER BY c.sort_order, i.sort_order, s.sku_id
                LIMIT 200
                """, (rs, row) -> new MenuRow(
                        bytesToUuid(rs.getBytes("item_id")),
                        rs.getString("item_name"),
                        rs.getString("description"),
                        rs.getString("taste_tags"),
                        rs.getString("allergen_tags"),
                        new CatalogView.Sku(
                                bytesToUuid(rs.getBytes("sku_id")),
                                rs.getString("sku_name"),
                                rs.getLong("price_cent"),
                                rs.getInt("available_quantity"),
                                rs.getLong("version"))), uuidToBytes(merchantId));
        Map<UUID, MenuBuilder> grouped = new LinkedHashMap<>();
        for (MenuRow row : rows) {
            grouped.computeIfAbsent(row.itemId(), ignored -> new MenuBuilder(row)).skus.add(row.sku());
        }
        return grouped.values().stream().map(MenuBuilder::build).toList();
    }

    @Override
    public CartSnapshot updateCart(
            UUID userId,
            UUID merchantId,
            UUID skuId,
            int quantity,
            Set<UUID> optionIds,
            Long expectedVersion,
            Instant now) {
        CartHeader cart = lockOrCreateCart(userId, merchantId, now);
        if (expectedVersion != null && expectedVersion != cart.version()) {
            throw new CommerceApplicationException(
                    "COMMERCE_CART_VERSION_CONFLICT", "购物车已更新，请刷新后重试");
        }
        SkuRow sku = loadActiveSku(merchantId, skuId);
        List<OptionRow> options = validateOptions(sku.itemId(), optionIds);
        String signature = optionSignature(optionIds);
        UUID existingItemId = jdbc.query("""
                SELECT cart_item_id
                FROM cart_item
                WHERE cart_id = ? AND sku_id = ? AND option_signature = ?
                FOR UPDATE
                """, rs -> rs.next() ? bytesToUuid(rs.getBytes("cart_item_id")) : null,
                uuidToBytes(cart.id()), uuidToBytes(skuId), signature);
        if (quantity == 0) {
            if (existingItemId != null) {
                jdbc.update("DELETE FROM cart_item_option WHERE cart_item_id = ?", uuidToBytes(existingItemId));
                jdbc.update("DELETE FROM cart_item WHERE cart_item_id = ?", uuidToBytes(existingItemId));
            }
        } else {
            Integer available = jdbc.queryForObject("""
                    SELECT available_quantity
                    FROM sku_inventory
                    WHERE sku_id = ?
                    """, Integer.class, uuidToBytes(skuId));
            if (available == null || available < quantity) {
                throw new CommerceApplicationException("COMMERCE_SKU_OUT_OF_STOCK", "商品库存不足");
            }
            UUID cartItemId = existingItemId == null ? UuidV7.generate() : existingItemId;
            if (existingItemId == null) {
                jdbc.update("""
                        INSERT INTO cart_item (
                          cart_item_id, cart_id, sku_id, quantity, option_signature,
                          created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, uuidToBytes(cartItemId), uuidToBytes(cart.id()), uuidToBytes(skuId),
                        quantity, signature, Timestamp.from(now), Timestamp.from(now));
            } else {
                jdbc.update("""
                        UPDATE cart_item
                        SET quantity = ?, updated_at = ?
                        WHERE cart_item_id = ?
                        """, quantity, Timestamp.from(now), uuidToBytes(cartItemId));
                jdbc.update("DELETE FROM cart_item_option WHERE cart_item_id = ?", uuidToBytes(cartItemId));
            }
            for (OptionRow option : options) {
                jdbc.update("""
                        INSERT INTO cart_item_option (
                          cart_item_id, option_id, option_name_snapshot, extra_price_cent_snapshot
                        ) VALUES (?, ?, ?, ?)
                        """, uuidToBytes(cartItemId), uuidToBytes(option.id()), option.name(), option.extraPriceCent());
            }
        }
        int updated = jdbc.update("""
                UPDATE shopping_cart
                SET version = version + 1, updated_at = ?
                WHERE cart_id = ? AND version = ?
                """, Timestamp.from(now), uuidToBytes(cart.id()), cart.version());
        if (updated != 1) {
            throw new CommerceApplicationException(
                    "COMMERCE_CART_VERSION_CONFLICT", "购物车已更新，请刷新后重试");
        }
        return loadCart(userId, merchantId).orElseThrow();
    }

    @Override
    public Optional<CartSnapshot> findCart(UUID userId, UUID merchantId) {
        return loadCart(userId, merchantId);
    }

    @Override
    public Optional<CheckoutQuote> findQuoteByRequestHash(UUID userId, byte[] requestHash) {
        UUID quoteId = jdbc.query("""
                SELECT quote_id
                FROM checkout_quote
                WHERE user_id = ? AND request_hash = ?
                """, rs -> rs.next() ? bytesToUuid(rs.getBytes("quote_id")) : null,
                uuidToBytes(userId), requestHash);
        return Optional.ofNullable(quoteId).map(this::loadQuote);
    }

    @Override
    public CheckoutQuote createQuote(
            UUID quoteId,
            UUID userId,
            UUID merchantId,
            UUID addressId,
            long expectedCartVersion,
            byte[] requestHash,
            Instant now,
            Instant expiresAt) {
        CartSnapshot cart = loadCart(userId, merchantId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_CART_NOT_FOUND", "购物车为空"));
        if (cart.version() != expectedCartVersion || cart.items().isEmpty()) {
            throw new CommerceApplicationException(
                    "COMMERCE_CART_VERSION_CONFLICT", "购物车已更新，请刷新后重试");
        }
        AddressRow address = loadAddress(userId, addressId);
        MerchantRow merchant = loadOpenMerchant(merchantId);
        Integer deliverable = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM merchant_delivery_zone
                WHERE merchant_id = ? AND zone_code = ?
                """, Integer.class, uuidToBytes(merchantId), address.zoneCode());
        if (deliverable == null || deliverable == 0) {
            throw new CommerceApplicationException(
                    "COMMERCE_ADDRESS_OUT_OF_RANGE", "该地址不在商家配送范围内");
        }
        List<CheckoutQuote.QuoteItem> items = quoteItems(cart.cartId());
        long itemAmount = items.stream().mapToLong(CheckoutQuote.QuoteItem::lineAmountCent).sum();
        if (itemAmount < merchant.minimumOrderCent()) {
            throw new CommerceApplicationException(
                    "COMMERCE_MINIMUM_ORDER_NOT_MET", "商品金额未达到起送价");
        }
        Long discount = jdbc.queryForObject("""
                SELECT COALESCE(MAX(discount_cent), 0)
                FROM promotion_rule
                WHERE status = 'ACTIVE' AND (merchant_id IS NULL OR merchant_id = ?)
                  AND starts_at <= ? AND ends_at > ? AND threshold_cent <= ?
                """, Long.class, uuidToBytes(merchantId), Timestamp.from(now), Timestamp.from(now), itemAmount);
        long discountCent = Math.min(discount == null ? 0 : discount,
                itemAmount + merchant.deliveryFeeCent() - 1);
        long payable = Math.addExact(itemAmount, merchant.deliveryFeeCent()) - discountCent;
        jdbc.update("""
                INSERT INTO checkout_quote (
                  quote_id, user_id, cart_id, merchant_id, address_id, cart_version,
                  item_amount_cent, delivery_fee_cent, discount_cent, payable_amount_cent,
                  currency, status, request_hash, expires_at, version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CNY', 'ACTIVE', ?, ?, 0, ?)
                """, uuidToBytes(quoteId), uuidToBytes(userId), uuidToBytes(cart.cartId()),
                uuidToBytes(merchantId), uuidToBytes(addressId), cart.version(), itemAmount,
                merchant.deliveryFeeCent(), discountCent, payable, requestHash,
                Timestamp.from(expiresAt), Timestamp.from(now));
        for (CheckoutQuote.QuoteItem item : items) {
            jdbc.update("""
                    INSERT INTO checkout_quote_item (
                      quote_item_id, quote_id, sku_id, item_name_snapshot, sku_name_snapshot,
                      option_summary_snapshot, unit_price_cent, quantity, line_amount_cent,
                      inventory_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, uuidToBytes(UuidV7.generate()), uuidToBytes(quoteId), uuidToBytes(item.skuId()),
                    item.itemName(), item.skuName(), item.optionSummary(), item.unitPriceCent(),
                    item.quantity(), item.lineAmountCent(), item.inventoryVersion());
        }
        return new CheckoutQuote(quoteId, userId, cart.cartId(), merchantId, addressId,
                cart.version(), itemAmount, merchant.deliveryFeeCent(), discountCent,
                payable, expiresAt, items);
    }

    @Override
    public Optional<FoodOrder> findOrderByRequestHash(UUID userId, byte[] requestHash) {
        UUID orderId = jdbc.query("""
                SELECT order_id
                FROM food_order
                WHERE user_id = ? AND request_hash = ?
                """, rs -> rs.next() ? bytesToUuid(rs.getBytes("order_id")) : null,
                uuidToBytes(userId), requestHash);
        return Optional.ofNullable(orderId).flatMap(this::findOrderById);
    }

    @Override
    public FoodOrder createOrder(
            UUID orderId,
            String orderNo,
            UUID userId,
            UUID quoteId,
            byte[] requestHash,
            Instant now,
            Instant expiresAt) {
        QuoteHeader quote = jdbc.query("""
                SELECT q.user_id, q.cart_id, q.merchant_id, q.address_id, q.item_amount_cent,
                       q.delivery_fee_cent, q.discount_cent, q.payable_amount_cent,
                       q.expires_at, q.status, m.name AS merchant_name, a.masked_summary
                FROM checkout_quote q
                JOIN merchant m ON m.merchant_id = q.merchant_id
                JOIN delivery_address a ON a.address_id = q.address_id AND a.user_id = q.user_id
                WHERE q.quote_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? new QuoteHeader(
                        bytesToUuid(rs.getBytes("user_id")),
                        bytesToUuid(rs.getBytes("cart_id")),
                        bytesToUuid(rs.getBytes("merchant_id")),
                        bytesToUuid(rs.getBytes("address_id")),
                        rs.getLong("item_amount_cent"),
                        rs.getLong("delivery_fee_cent"),
                        rs.getLong("discount_cent"),
                        rs.getLong("payable_amount_cent"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status"),
                        rs.getString("merchant_name"),
                        rs.getString("masked_summary")) : null,
                uuidToBytes(quoteId));
        if (quote == null || !quote.userId().equals(userId)) {
            throw new CommerceApplicationException("COMMERCE_QUOTE_NOT_FOUND", "结算报价不存在或不可访问");
        }
        if (!"ACTIVE".equals(quote.status()) || !quote.expiresAt().isAfter(now)) {
            throw new CommerceApplicationException("COMMERCE_QUOTE_EXPIRED", "结算报价已过期，请重新结算");
        }
        List<CheckoutQuote.QuoteItem> items = quoteItemsByQuote(quoteId);
        Map<UUID, ReservationRequest> reservations = new LinkedHashMap<>();
        for (CheckoutQuote.QuoteItem item : items) {
            reservations.merge(item.skuId(),
                    new ReservationRequest(item.quantity(), item.inventoryVersion()),
                    (left, right) -> {
                        if (left.inventoryVersion() != right.inventoryVersion()) {
                            throw new CommerceApplicationException(
                                    "COMMERCE_QUOTE_STALE", "商品库存或价格已变化，请重新结算");
                        }
                        return new ReservationRequest(
                                Math.addExact(left.quantity(), right.quantity()), left.inventoryVersion());
                    });
        }
        for (Map.Entry<UUID, ReservationRequest> entry : reservations.entrySet()) {
            UUID skuId = entry.getKey();
            ReservationRequest reservation = entry.getValue();
            InventoryRow inventory = jdbc.query("""
                    SELECT available_quantity, reserved_quantity, version
                    FROM sku_inventory
                    WHERE sku_id = ?
                    FOR UPDATE
                    """, rs -> rs.next() ? new InventoryRow(
                            rs.getInt("available_quantity"),
                            rs.getInt("reserved_quantity"),
                            rs.getLong("version")) : null,
                    uuidToBytes(skuId));
            if (inventory == null || inventory.available() < reservation.quantity()
                    || inventory.version() != reservation.inventoryVersion()) {
                throw new CommerceApplicationException(
                        "COMMERCE_QUOTE_STALE", "商品库存或价格已变化，请重新结算");
            }
            int reserved = jdbc.update("""
                    UPDATE sku_inventory
                    SET available_quantity = available_quantity - ?,
                        reserved_quantity = reserved_quantity + ?,
                        version = version + 1, updated_at = ?
                    WHERE sku_id = ? AND version = ? AND available_quantity >= ?
                    """, reservation.quantity(), reservation.quantity(), Timestamp.from(now),
                    uuidToBytes(skuId), inventory.version(), reservation.quantity());
            if (reserved != 1) {
                throw new CommerceApplicationException(
                        "COMMERCE_QUOTE_STALE", "商品库存或价格已变化，请重新结算");
            }
        }
        jdbc.update("""
                INSERT INTO food_order (
                  order_id, order_no, user_id, merchant_id, merchant_name_snapshot,
                  address_id, address_summary_snapshot, quote_id, payment_order_id,
                  item_amount_cent, delivery_fee_cent, discount_cent, payable_amount_cent,
                  currency, status, payment_status, refund_status, request_hash,
                  expires_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 'CNY',
                          'PENDING_PAYMENT', 'UNPAID', 'NONE', ?, ?, 0, ?, ?)
                """, uuidToBytes(orderId), orderNo, uuidToBytes(userId), uuidToBytes(quote.merchantId()),
                quote.merchantName(), uuidToBytes(quote.addressId()), quote.addressSummary(),
                uuidToBytes(quoteId), quote.itemAmountCent(), quote.deliveryFeeCent(),
                quote.discountCent(), quote.payableAmountCent(), requestHash,
                Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
        for (CheckoutQuote.QuoteItem item : items) {
            jdbc.update("""
                    INSERT INTO food_order_item (
                      order_item_id, order_id, sku_id, item_name_snapshot, sku_name_snapshot,
                      option_summary_snapshot, unit_price_cent, quantity, line_amount_cent
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, uuidToBytes(UuidV7.generate()), uuidToBytes(orderId), uuidToBytes(item.skuId()),
                    item.itemName(), item.skuName(), item.optionSummary(), item.unitPriceCent(),
                    item.quantity(), item.lineAmountCent());
        }
        jdbc.update("UPDATE checkout_quote SET status = 'CONSUMED', version = version + 1 WHERE quote_id = ?",
                uuidToBytes(quoteId));
        appendHistory(orderId, null, FoodOrderStatus.PENDING_PAYMENT, "ORDER_CREATED", "COMMERCE", now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderNo", orderNo);
        payload.put("userId", userId);
        payload.put("quoteId", quoteId);
        payload.put("status", FoodOrderStatus.PENDING_PAYMENT.name());
        payload.put("paymentStatus", PaymentStatus.UNPAID.name());
        payload.put("refundStatus", RefundStatus.NONE.name());
        payload.put("amountCent", quote.payableAmountCent());
        payload.put("currency", "CNY");
        appendOutbox("commerce.food-order.created", "FOOD_ORDER", orderId, payload, now);
        return findOrderById(orderId).orElseThrow();
    }

    @Override
    public Optional<FoodOrder> findOrder(UUID userId, UUID orderId) {
        return queryOrder("WHERE order_id = ? AND user_id = ?", uuidToBytes(orderId), uuidToBytes(userId));
    }

    @Override
    public Optional<FoodOrder> findOrderById(UUID orderId) {
        return queryOrder("WHERE order_id = ?", uuidToBytes(orderId));
    }

    @Override
    public boolean saveTransition(
            FoodOrder before,
            FoodOrder after,
            String reasonCode,
            String source,
            String eventType,
            Map<String, Object> eventPayload) {
        int changed = jdbc.update("""
                UPDATE food_order
                SET payment_order_id = ?, status = ?, payment_status = ?, refund_status = ?,
                    version = ?, updated_at = ?
                WHERE order_id = ? AND version = ? AND status = ?
                """, after.paymentOrderId() == null ? null : uuidToBytes(after.paymentOrderId()),
                after.status().name(), after.paymentStatus().name(), after.refundStatus().name(),
                after.version(), Timestamp.from(after.updatedAt()), uuidToBytes(after.id()),
                before.version(), before.status().name());
        if (changed != 1) return false;
        appendHistory(after.id(), before.status(), after.status(), reasonCode, source, after.updatedAt());
        updateDeliveryTask(after);
        appendOutbox(eventType, "FOOD_ORDER", after.id(), eventPayload, after.updatedAt());
        return true;
    }

    @Override
    public void releaseReservation(UUID orderId, Instant now) {
        int changed = jdbc.update("""
                UPDATE sku_inventory inv
                JOIN (
                  SELECT sku_id, SUM(quantity) AS quantity
                  FROM food_order_item
                  WHERE order_id = ?
                  GROUP BY sku_id
                ) item ON item.sku_id = inv.sku_id
                SET inv.available_quantity = inv.available_quantity + item.quantity,
                    inv.reserved_quantity = inv.reserved_quantity - item.quantity,
                    inv.version = inv.version + 1,
                    inv.updated_at = ?
                WHERE inv.reserved_quantity >= item.quantity
                """, uuidToBytes(orderId), Timestamp.from(now));
        if (changed == 0) {
            throw new CommerceApplicationException(
                    "COMMERCE_INVENTORY_RESERVATION_MISSING", "订单库存预留状态异常");
        }
    }

    @Override
    public void consumeReservation(UUID orderId, Instant now) {
        int changed = jdbc.update("""
                UPDATE sku_inventory inv
                JOIN (
                  SELECT sku_id, SUM(quantity) AS quantity
                  FROM food_order_item
                  WHERE order_id = ?
                  GROUP BY sku_id
                ) item ON item.sku_id = inv.sku_id
                SET inv.reserved_quantity = inv.reserved_quantity - item.quantity,
                    inv.version = inv.version + 1,
                    inv.updated_at = ?
                WHERE inv.reserved_quantity >= item.quantity
                """, uuidToBytes(orderId), Timestamp.from(now));
        if (changed == 0) {
            throw new CommerceApplicationException(
                    "COMMERCE_INVENTORY_RESERVATION_MISSING", "订单库存预留状态异常");
        }
    }

    @Override
    public boolean claimInbox(UUID eventId, String consumerName, String eventType, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO inbox_message (
                      event_id, consumer_name, event_type, received_at, processed_at
                    ) VALUES (?, ?, ?, ?, NULL)
                    """, uuidToBytes(eventId), consumerName, eventType, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public void completeInbox(UUID eventId, String consumerName, Instant now) {
        jdbc.update("""
                UPDATE inbox_message
                SET processed_at = COALESCE(processed_at, ?)
                WHERE event_id = ? AND consumer_name = ?
                """, Timestamp.from(now), uuidToBytes(eventId), consumerName);
    }

    @Override
    public List<FoodOrder> findOrdersForProgress(
            FoodOrderStatus status, Instant updatedBefore, int limit) {
        return jdbc.query(orderSelect() + """
                WHERE status = ? AND updated_at <= ?
                ORDER BY updated_at, order_id
                LIMIT ?
                """, this::mapOrder, status.name(), Timestamp.from(updatedBefore), limit);
    }

    @Override
    public List<DeliveryAddress> listAddresses(UUID userId, int limit) {
        return jdbc.query("""
                SELECT address_id, label, masked_summary, zone_code, is_default,
                       version, created_at, updated_at
                FROM delivery_address
                WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY is_default DESC, updated_at DESC, address_id
                LIMIT ?
                """, (rs, row) -> new DeliveryAddress(
                        bytesToUuid(rs.getBytes("address_id")), rs.getString("label"),
                        rs.getString("masked_summary"), rs.getString("zone_code"),
                        rs.getBoolean("is_default"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                uuidToBytes(userId), Math.min(Math.max(limit, 1), 50));
    }

    @Override
    public DeliveryAddress createAddress(
            UUID addressId,
            UUID userId,
            String label,
            String maskedSummary,
            byte[] recipientCiphertext,
            byte[] mobileCiphertext,
            byte[] addressCiphertext,
            int encryptionKeyVersion,
            String zoneCode,
            boolean defaultAddress,
            Instant now) {
        if (defaultAddress) {
            jdbc.update("""
                    UPDATE delivery_address
                    SET is_default = FALSE, version = version + 1, updated_at = ?
                    WHERE user_id = ? AND status = 'ACTIVE' AND is_default = TRUE
                    """, Timestamp.from(now), uuidToBytes(userId));
        }
        jdbc.update("""
                INSERT INTO delivery_address (
                  address_id, user_id, label, masked_summary, recipient_ciphertext,
                  mobile_ciphertext, address_ciphertext, encryption_key_version,
                  zone_code, is_default, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                """, uuidToBytes(addressId), uuidToBytes(userId), label, maskedSummary,
                recipientCiphertext, mobileCiphertext, addressCiphertext, encryptionKeyVersion,
                zoneCode, defaultAddress, Timestamp.from(now), Timestamp.from(now));
        return listAddresses(userId, 50).stream()
                .filter(address -> address.id().equals(addressId)).findFirst().orElseThrow();
    }

    private CartHeader lockOrCreateCart(UUID userId, UUID merchantId, Instant now) {
        CartHeader existing = jdbc.query("""
                SELECT cart_id, version
                FROM shopping_cart
                WHERE user_id = ? AND merchant_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? new CartHeader(
                        bytesToUuid(rs.getBytes("cart_id")), rs.getLong("version")) : null,
                uuidToBytes(userId), uuidToBytes(merchantId));
        if (existing != null) return existing;
        loadOpenMerchant(merchantId);
        UUID cartId = UuidV7.generate();
        jdbc.update("""
                INSERT INTO shopping_cart (
                  cart_id, user_id, merchant_id, version, created_at, updated_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """, uuidToBytes(cartId), uuidToBytes(userId), uuidToBytes(merchantId),
                Timestamp.from(now), Timestamp.from(now));
        return new CartHeader(cartId, 0);
    }

    private SkuRow loadActiveSku(UUID merchantId, UUID skuId) {
        SkuRow row = jdbc.query("""
                SELECT s.sku_id, i.item_id, s.price_cent
                FROM menu_sku s
                JOIN menu_item i ON i.item_id = s.item_id AND i.status = 'ACTIVE'
                JOIN merchant m ON m.merchant_id = i.merchant_id AND m.status = 'OPEN'
                WHERE s.sku_id = ? AND i.merchant_id = ? AND s.status = 'ACTIVE'
                """, rs -> rs.next() ? new SkuRow(
                        bytesToUuid(rs.getBytes("sku_id")),
                        bytesToUuid(rs.getBytes("item_id")),
                        rs.getLong("price_cent")) : null,
                uuidToBytes(skuId), uuidToBytes(merchantId));
        if (row == null) {
            throw new CommerceApplicationException("COMMERCE_SKU_NOT_FOUND", "商品规格不存在或已下架");
        }
        return row;
    }

    private List<OptionRow> validateOptions(UUID itemId, Set<UUID> optionIds) {
        List<OptionGroupRow> groups = jdbc.query("""
                SELECT option_group_id, required_flag, min_select, max_select
                FROM sku_option_group
                WHERE item_id = ?
                ORDER BY sort_order
                LIMIT 50
                """, (rs, row) -> new OptionGroupRow(
                        bytesToUuid(rs.getBytes("option_group_id")),
                        rs.getBoolean("required_flag"),
                        rs.getInt("min_select"),
                        rs.getInt("max_select")), uuidToBytes(itemId));
        List<OptionRow> selected = new ArrayList<>();
        for (UUID optionId : optionIds) {
            OptionRow option = jdbc.query("""
                    SELECT o.option_id, o.option_group_id, o.name, o.extra_price_cent
                    FROM sku_option o
                    JOIN sku_option_group g ON g.option_group_id = o.option_group_id
                    WHERE o.option_id = ? AND g.item_id = ? AND o.status = 'ACTIVE'
                    """, rs -> rs.next() ? new OptionRow(
                            bytesToUuid(rs.getBytes("option_id")),
                            bytesToUuid(rs.getBytes("option_group_id")),
                            rs.getString("name"),
                            rs.getLong("extra_price_cent")) : null,
                    uuidToBytes(optionId), uuidToBytes(itemId));
            if (option == null) {
                throw new CommerceApplicationException(
                        "COMMERCE_OPTION_INVALID", "商品规格选项无效");
            }
            selected.add(option);
        }
        for (OptionGroupRow group : groups) {
            long count = selected.stream().filter(option -> option.groupId().equals(group.id())).count();
            int minimum = group.required() ? Math.max(1, group.minSelect()) : group.minSelect();
            if (count < minimum || count > group.maxSelect()) {
                throw new CommerceApplicationException(
                        "COMMERCE_OPTION_SELECTION_INVALID", "请选择完整的商品规格");
            }
        }
        selected.sort(Comparator.comparing(option -> option.id().toString()));
        return selected;
    }

    private Optional<CartSnapshot> loadCart(UUID userId, UUID merchantId) {
        CartViewHeader header = jdbc.query("""
                SELECT c.cart_id, c.version, m.name AS merchant_name
                FROM shopping_cart c
                JOIN merchant m ON m.merchant_id = c.merchant_id
                WHERE c.user_id = ? AND c.merchant_id = ?
                """, rs -> rs.next() ? new CartViewHeader(
                        bytesToUuid(rs.getBytes("cart_id")),
                        rs.getLong("version"),
                        rs.getString("merchant_name")) : null,
                uuidToBytes(userId), uuidToBytes(merchantId));
        if (header == null) return Optional.empty();
        List<CartSnapshot.Item> items = jdbc.query("""
                SELECT ci.cart_item_id, ci.sku_id, mi.name AS item_name, ms.name AS sku_name,
                       COALESCE(GROUP_CONCAT(cio.option_name_snapshot ORDER BY cio.option_id SEPARATOR '、'), '') AS option_summary,
                       ms.price_cent + COALESCE(SUM(cio.extra_price_cent_snapshot), 0) AS unit_price_cent,
                       ci.quantity,
                       (ms.price_cent + COALESCE(SUM(cio.extra_price_cent_snapshot), 0)) * ci.quantity AS line_amount_cent
                FROM cart_item ci
                JOIN menu_sku ms ON ms.sku_id = ci.sku_id
                JOIN menu_item mi ON mi.item_id = ms.item_id
                LEFT JOIN cart_item_option cio ON cio.cart_item_id = ci.cart_item_id
                WHERE ci.cart_id = ?
                GROUP BY ci.cart_item_id, ci.sku_id, mi.name, ms.name, ms.price_cent, ci.quantity
                ORDER BY ci.created_at, ci.cart_item_id
                LIMIT 100
                """, (rs, row) -> new CartSnapshot.Item(
                        bytesToUuid(rs.getBytes("cart_item_id")),
                        bytesToUuid(rs.getBytes("sku_id")),
                        rs.getString("item_name"), rs.getString("sku_name"),
                        rs.getString("option_summary"), rs.getLong("unit_price_cent"),
                        rs.getInt("quantity"), rs.getLong("line_amount_cent")),
                uuidToBytes(header.id()));
        return Optional.of(new CartSnapshot(header.id(), userId, merchantId, header.merchantName(),
                header.version(), items, items.stream().mapToLong(CartSnapshot.Item::lineAmountCent).sum()));
    }

    private List<CheckoutQuote.QuoteItem> quoteItems(UUID cartId) {
        List<CheckoutQuote.QuoteItem> items = jdbc.query("""
                SELECT ci.sku_id, mi.name AS item_name, ms.name AS sku_name,
                       COALESCE(GROUP_CONCAT(cio.option_name_snapshot ORDER BY cio.option_id SEPARATOR '、'), '') AS option_summary,
                       ms.price_cent + COALESCE(SUM(cio.extra_price_cent_snapshot), 0) AS unit_price_cent,
                       ci.quantity,
                       (ms.price_cent + COALESCE(SUM(cio.extra_price_cent_snapshot), 0)) * ci.quantity AS line_amount_cent,
                       inv.available_quantity, inv.version
                FROM cart_item ci
                JOIN menu_sku ms ON ms.sku_id = ci.sku_id AND ms.status = 'ACTIVE'
                JOIN menu_item mi ON mi.item_id = ms.item_id AND mi.status = 'ACTIVE'
                JOIN sku_inventory inv ON inv.sku_id = ci.sku_id
                LEFT JOIN cart_item_option cio ON cio.cart_item_id = ci.cart_item_id
                WHERE ci.cart_id = ?
                GROUP BY ci.cart_item_id, ci.sku_id, mi.name, ms.name, ms.price_cent,
                         ci.quantity, inv.available_quantity, inv.version
                ORDER BY ci.created_at, ci.cart_item_id
                LIMIT 100
                """, (rs, row) -> {
                    int quantity = rs.getInt("quantity");
                    if (rs.getInt("available_quantity") < quantity) {
                        throw new CommerceApplicationException("COMMERCE_SKU_OUT_OF_STOCK", "商品库存不足");
                    }
                    return new CheckoutQuote.QuoteItem(
                            bytesToUuid(rs.getBytes("sku_id")), rs.getString("item_name"),
                            rs.getString("sku_name"), rs.getString("option_summary"),
                            rs.getLong("unit_price_cent"), quantity,
                            rs.getLong("line_amount_cent"), rs.getLong("version"));
                }, uuidToBytes(cartId));
        if (items.isEmpty()) {
            throw new CommerceApplicationException("COMMERCE_CART_NOT_FOUND", "购物车为空");
        }
        return items;
    }

    private List<CheckoutQuote.QuoteItem> quoteItemsByQuote(UUID quoteId) {
        return jdbc.query("""
                SELECT sku_id, item_name_snapshot, sku_name_snapshot, option_summary_snapshot,
                       unit_price_cent, quantity, line_amount_cent, inventory_version
                FROM checkout_quote_item
                WHERE quote_id = ?
                ORDER BY quote_item_id
                LIMIT 100
                """, (rs, row) -> new CheckoutQuote.QuoteItem(
                        bytesToUuid(rs.getBytes("sku_id")),
                        rs.getString("item_name_snapshot"), rs.getString("sku_name_snapshot"),
                        rs.getString("option_summary_snapshot"), rs.getLong("unit_price_cent"),
                        rs.getInt("quantity"), rs.getLong("line_amount_cent"),
                        rs.getLong("inventory_version")), uuidToBytes(quoteId));
    }

    private CheckoutQuote loadQuote(UUID quoteId) {
        QuoteCore core = jdbc.query("""
                SELECT user_id, cart_id, merchant_id, address_id, cart_version,
                       item_amount_cent, delivery_fee_cent, discount_cent,
                       payable_amount_cent, expires_at
                FROM checkout_quote
                WHERE quote_id = ?
                """, rs -> rs.next() ? new QuoteCore(
                        bytesToUuid(rs.getBytes("user_id")), bytesToUuid(rs.getBytes("cart_id")),
                        bytesToUuid(rs.getBytes("merchant_id")), bytesToUuid(rs.getBytes("address_id")),
                        rs.getLong("cart_version"), rs.getLong("item_amount_cent"),
                        rs.getLong("delivery_fee_cent"), rs.getLong("discount_cent"),
                        rs.getLong("payable_amount_cent"), rs.getTimestamp("expires_at").toInstant()) : null,
                uuidToBytes(quoteId));
        if (core == null) throw new CommerceApplicationException("COMMERCE_QUOTE_NOT_FOUND", "结算报价不存在");
        return new CheckoutQuote(quoteId, core.userId(), core.cartId(), core.merchantId(),
                core.addressId(), core.cartVersion(), core.itemAmountCent(), core.deliveryFeeCent(),
                core.discountCent(), core.payableAmountCent(), core.expiresAt(), quoteItemsByQuote(quoteId));
    }

    private AddressRow loadAddress(UUID userId, UUID addressId) {
        AddressRow row = jdbc.query("""
                SELECT zone_code, masked_summary
                FROM delivery_address
                WHERE address_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, rs -> rs.next() ? new AddressRow(
                        rs.getString("zone_code"), rs.getString("masked_summary")) : null,
                uuidToBytes(addressId), uuidToBytes(userId));
        if (row == null) {
            throw new CommerceApplicationException(
                    "COMMERCE_ADDRESS_NOT_FOUND", "配送地址不存在或不可访问");
        }
        return row;
    }

    private MerchantRow loadOpenMerchant(UUID merchantId) {
        MerchantRow row = jdbc.query("""
                SELECT name, minimum_order_cent, delivery_fee_cent
                FROM merchant
                WHERE merchant_id = ? AND status = 'OPEN'
                """, rs -> rs.next() ? new MerchantRow(
                        rs.getString("name"), rs.getLong("minimum_order_cent"),
                        rs.getLong("delivery_fee_cent")) : null,
                uuidToBytes(merchantId));
        if (row == null) {
            throw new CommerceApplicationException("COMMERCE_MERCHANT_CLOSED", "商家当前未营业");
        }
        return row;
    }

    private Optional<FoodOrder> queryOrder(String where, Object... arguments) {
        List<FoodOrder> rows = jdbc.query(orderSelect() + where, this::mapOrder, arguments);
        return rows.stream().findFirst();
    }

    private String orderSelect() {
        return """
                SELECT order_id, order_no, user_id, merchant_id, merchant_name_snapshot,
                       address_id, address_summary_snapshot, quote_id, payment_order_id,
                       item_amount_cent, delivery_fee_cent, discount_cent, payable_amount_cent,
                       status, payment_status, refund_status, expires_at, version, created_at, updated_at
                FROM food_order
                """;
    }

    private FoodOrder mapOrder(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        byte[] paymentBytes = rs.getBytes("payment_order_id");
        return new FoodOrder(
                bytesToUuid(rs.getBytes("order_id")), rs.getString("order_no"),
                bytesToUuid(rs.getBytes("user_id")), bytesToUuid(rs.getBytes("merchant_id")),
                rs.getString("merchant_name_snapshot"), bytesToUuid(rs.getBytes("address_id")),
                rs.getString("address_summary_snapshot"), bytesToUuid(rs.getBytes("quote_id")),
                paymentBytes == null ? null : bytesToUuid(paymentBytes),
                rs.getLong("item_amount_cent"), rs.getLong("delivery_fee_cent"),
                rs.getLong("discount_cent"), rs.getLong("payable_amount_cent"),
                FoodOrderStatus.valueOf(rs.getString("status")),
                PaymentStatus.valueOf(rs.getString("payment_status")),
                RefundStatus.valueOf(rs.getString("refund_status")),
                rs.getTimestamp("expires_at").toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private void appendHistory(
            UUID orderId,
            FoodOrderStatus from,
            FoodOrderStatus to,
            String reason,
            String source,
            Instant at) {
        jdbc.update("""
                INSERT INTO food_order_status_history (
                  history_id, order_id, from_status, to_status, reason_code, source, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, uuidToBytes(UuidV7.generate()), uuidToBytes(orderId),
                from == null ? null : from.name(), to.name(), reason, source, Timestamp.from(at));
    }

    private void updateDeliveryTask(FoodOrder order) {
        if (order.status() == FoodOrderStatus.PAID) {
            jdbc.update("""
                    INSERT INTO delivery_task (
                      delivery_task_id, order_id, status, estimated_delivered_at,
                      version, created_at, updated_at
                    ) VALUES (?, ?, 'CREATED', DATE_ADD(?, INTERVAL 30 MINUTE), 0, ?, ?)
                    ON DUPLICATE KEY UPDATE updated_at = updated_at
                    """, uuidToBytes(UuidV7.generate()), uuidToBytes(order.id()),
                    Timestamp.from(order.updatedAt()), Timestamp.from(order.updatedAt()),
                    Timestamp.from(order.updatedAt()));
            return;
        }
        String deliveryStatus = switch (order.status()) {
            case MERCHANT_ACCEPTED -> "ACCEPTED";
            case PREPARING -> "PICKUP_PENDING";
            case DELIVERING -> "DELIVERING";
            case DELIVERED -> "DELIVERED";
            case CANCELLATION_PENDING, CANCELLED, REFUND_FAILED -> "CANCELLED";
            default -> null;
        };
        if (deliveryStatus != null) {
            jdbc.update("""
                    UPDATE delivery_task
                    SET status = ?, version = version + 1, updated_at = ?
                    WHERE order_id = ? AND status <> ?
                    """, deliveryStatus, Timestamp.from(order.updatedAt()),
                    uuidToBytes(order.id()), deliveryStatus);
        }
    }

    private void appendOutbox(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, Object> payload,
            Instant at) {
        UUID eventId = UuidV7.generate();
        jdbc.update("""
                INSERT INTO outbox_event (
                  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
                  trace_id, payload_version, payload, status, attempts, next_attempt_at,
                  lease_owner, lease_until, last_error, published_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'PENDING', 0, ?, NULL, NULL, NULL, NULL, ?)
                """, uuidToBytes(eventId), eventType, aggregateType, uuidToBytes(aggregateId),
                Timestamp.from(at), eventId.toString(), json(payload), Timestamp.from(at), Timestamp.from(at));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Commerce event", exception);
        }
    }

    private static String optionSignature(Set<UUID> optionIds) {
        try {
            String normalized = optionIds.stream().map(UUID::toString).sorted()
                    .reduce((left, right) -> left + "," + right).orElse("");
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array();
    }

    public static UUID bytesToUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record MenuRow(
            UUID itemId, String name, String description, String tasteTags,
            String allergenTags, CatalogView.Sku sku) {
    }

    private static final class MenuBuilder {
        private final MenuRow row;
        private final List<CatalogView.Sku> skus = new ArrayList<>();

        private MenuBuilder(MenuRow row) {
            this.row = row;
        }

        private CatalogView.MenuItem build() {
            return new CatalogView.MenuItem(row.itemId(), row.name(), row.description(),
                    row.tasteTags(), row.allergenTags(), skus);
        }
    }

    private record CartHeader(UUID id, long version) {
    }

    private record CartViewHeader(UUID id, long version, String merchantName) {
    }

    private record SkuRow(UUID id, UUID itemId, long priceCent) {
    }

    private record OptionRow(UUID id, UUID groupId, String name, long extraPriceCent) {
    }

    private record OptionGroupRow(UUID id, boolean required, int minSelect, int maxSelect) {
    }

    private record AddressRow(String zoneCode, String summary) {
    }

    private record MerchantRow(String name, long minimumOrderCent, long deliveryFeeCent) {
    }

    private record InventoryRow(int available, int reserved, long version) {
    }

    private record ReservationRequest(int quantity, long inventoryVersion) {
    }

    private record QuoteCore(
            UUID userId, UUID cartId, UUID merchantId, UUID addressId, long cartVersion,
            long itemAmountCent, long deliveryFeeCent, long discountCent,
            long payableAmountCent, Instant expiresAt) {
    }

    private record QuoteHeader(
            UUID userId, UUID cartId, UUID merchantId, UUID addressId,
            long itemAmountCent, long deliveryFeeCent, long discountCent,
            long payableAmountCent, Instant expiresAt, String status,
            String merchantName, String addressSummary) {
    }
}
