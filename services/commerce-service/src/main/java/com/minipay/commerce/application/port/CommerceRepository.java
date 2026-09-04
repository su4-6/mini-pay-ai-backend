package com.minipay.commerce.application.port;

import com.minipay.commerce.domain.model.CartSnapshot;
import com.minipay.commerce.domain.model.CatalogView;
import com.minipay.commerce.domain.model.CheckoutQuote;
import com.minipay.commerce.domain.model.FoodOrder;
import com.minipay.commerce.domain.model.FoodOrderStatus;
import com.minipay.commerce.domain.model.DeliveryAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CommerceRepository {
    List<CatalogView.Merchant> searchMerchants(
            String zoneCode, String categoryCode, Long maxDeliveryFeeCent,
            Integer maxDeliveryMinutes, int limit);

    List<CatalogView.MenuItem> menu(UUID merchantId);

    CartSnapshot updateCart(
            UUID userId, UUID merchantId, UUID skuId, int quantity,
            Set<UUID> optionIds, Long expectedVersion, Instant now);

    Optional<CartSnapshot> findCart(UUID userId, UUID merchantId);

    Optional<CheckoutQuote> findQuoteByRequestHash(UUID userId, byte[] requestHash);

    CheckoutQuote createQuote(
            UUID quoteId, UUID userId, UUID merchantId, UUID addressId,
            long expectedCartVersion, byte[] requestHash, Instant now, Instant expiresAt);

    Optional<FoodOrder> findOrderByRequestHash(UUID userId, byte[] requestHash);

    FoodOrder createOrder(
            UUID orderId, String orderNo, UUID userId, UUID quoteId,
            byte[] requestHash, Instant now, Instant expiresAt);

    Optional<FoodOrder> findOrder(UUID userId, UUID orderId);

    Optional<FoodOrder> findOrderById(UUID orderId);

    boolean saveTransition(
            FoodOrder before, FoodOrder after, String reasonCode, String source,
            String eventType, Map<String, Object> eventPayload);

    void releaseReservation(UUID orderId, Instant now);

    void consumeReservation(UUID orderId, Instant now);

    boolean claimInbox(UUID eventId, String consumerName, String eventType, Instant now);

    void completeInbox(UUID eventId, String consumerName, Instant now);

    List<FoodOrder> findOrdersForProgress(FoodOrderStatus status, Instant updatedBefore, int limit);

    List<DeliveryAddress> listAddresses(UUID userId, int limit);

    DeliveryAddress createAddress(
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
            Instant now);
}
