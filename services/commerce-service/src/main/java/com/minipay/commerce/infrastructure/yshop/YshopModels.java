package com.minipay.commerce.infrastructure.yshop;

import java.util.List;

public final class YshopModels {
    private YshopModels() { }

    public record IdentityRequest(
            String subject, String nickname, String phone,
            String avatarFetchUrl, long profileVersion) { }
    public record IdentityView(
            String provider, String subject, long memberId, String username, boolean created) { }
    public record Store(long shopId, String name, String image, String address,
                        long distanceMeters, boolean open, boolean deliverable,
                        long minimumOrderCent, long deliveryFeeCent) { }
    public record Sku(long skuId, String sku, long priceCent, int stock,
                      boolean available, String image) { }
    public record Product(long productId, String name, String description,
                          String image, List<Sku> skus) { }
    public record Address(long addressId, String label, boolean defaultAddress,
                          String maskedRecipient, String maskedPhone,
                          double longitude, double latitude) { }
    public record QuoteItemRequest(long skuId, int quantity) { }
    public record QuoteRequest(String subject, long shopId, Long addressId,
                               String fulfillmentType, List<QuoteItemRequest> items) { }
    public record QuoteItem(long skuId, long productId, String sku, String name,
                            String image, int quantity, long unitPriceCent, long lineAmountCent) { }
    public record Quote(String quoteId, long shopId, String shopName,
                        String fulfillmentType, List<QuoteItem> items,
                        long subtotalCent, long deliveryFeeCent, long amountCent,
                        String currency, String expiresAt) { }
    public record CreateOrderRequest(String subject, String quoteId, String remark) { }
    public record OrderItem(long productId, String name, String sku, String image,
                            int quantity, long unitPriceCent, long lineAmountCent) { }
    public record Order(String orderRefId, String externalOrderNo, long shopId, String shopName,
                        long amountCent, String currency, String paymentOrderId,
                        String paymentStatus, String fulfillmentStatus, String refundStatus,
                        String fulfillmentType, String recipient, String phone, String address,
                        String createdAt, String expiresAt, List<OrderItem> items,
                        int totalQuantity, long subtotalCent, long deliveryFeeCent,
                        long discountCent) { }
    public record PaymentResult(String eventId, String subject, String paymentOrderId,
                                long amountCent, String currency, String status) { }
    public record CloseResult(String eventId, String subject, String paymentOrderId, String reason) { }
    public record CancellationRequest(String subject, String reason) { }
    public record EventResult(boolean accepted, String currentStatus) { }
    public record RefundResult(String eventId, String subject, String paymentOrderId,
                               String refundRequestId, String status) { }
}
