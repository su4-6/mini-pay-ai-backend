package co.yixiang.yshop.module.minipay.service;

import java.util.List;

public final class MiniPayFoodModels {
    private MiniPayFoodModels() { }

    public record IdentityRequest(String subject, String nickname, String phone,
                                  String avatarFetchUrl, long profileVersion) { }
    public record IdentityView(
            String provider, String subject, long memberId, String username, boolean created) { }
    public record StoreView(long shopId, String name, String image, String address,
                            long distanceMeters, boolean open, boolean deliverable,
                            long minimumOrderCent, long deliveryFeeCent) { }
    public record SkuView(long skuId, String sku, long priceCent, int stock,
                          boolean available, String image) { }
    public record ProductView(long productId, String name, String description,
                              String image, List<SkuView> skus) { }
    public record AddressView(long addressId, String label, boolean defaultAddress,
                              String maskedRecipient, String maskedPhone,
                              double longitude, double latitude) { }
    public record AddressLocationDraftView(String draftId, String displayAddress,
                                           String expiresAt) { }
    public record CreatedAddressView(long addressId, String displayAddress,
                                     boolean defaultAddress) { }
    public record QuoteItemRequest(long skuId, int quantity) { }
    public record QuoteRequest(String subject, long shopId, Long addressId,
                               String fulfillmentType, List<QuoteItemRequest> items) { }
    public record QuoteItem(long skuId, long productId, String sku, String name,
                            String image, int quantity, long unitPriceCent, long lineAmountCent) { }
    public record QuoteView(String quoteId, long shopId, String shopName,
                            String fulfillmentType, List<QuoteItem> items,
                            long subtotalCent, long deliveryFeeCent, long amountCent,
                            String currency, String expiresAt) { }
    public record CreateOrderRequest(String subject, String quoteId, String remark) { }
    public record PaymentResultRequest(String eventId, String subject, String paymentOrderId,
                                       long amountCent, String currency, String status) { }
    public record CloseRequest(String eventId, String subject, String paymentOrderId, String reason) { }
    public record CancellationRequest(String subject, String reason) { }
    public record RefundResultRequest(String eventId, String subject, String paymentOrderId,
                                      String refundRequestId, String status) { }
    public record OrderItemView(long productId, String name, String sku, String image,
                                int quantity, long unitPriceCent, long lineAmountCent) { }
    public record OrderView(String orderRefId, String externalOrderNo, long shopId, String shopName,
                            long amountCent, String currency, String paymentOrderId,
                            String paymentStatus, String fulfillmentStatus, String refundStatus,
                            String fulfillmentType, String recipient, String phone, String address,
                            String createdAt, String expiresAt, List<OrderItemView> items,
                            int totalQuantity, long subtotalCent, long deliveryFeeCent,
                            long discountCent) { }
    public record EventResult(boolean accepted, String currentStatus) { }
}
