package com.minipay.agent.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AgentBusinessGateway {
    Map<String, Object> resolveRecipient(String bearerToken, String exactMobile);

    List<Map<String, Object>> resolveExactFriend(String bearerToken, String query);

    Map<String, Object> walletSummary(String bearerToken);

    Map<String, Object> aggregateBills(String bearerToken, Instant from, Instant to);

    Map<String, Object> listBills(String bearerToken, Instant from, Instant to,
                                  String direction, String businessType, String status,
                                  int page, int size);

    Map<String, Object> prepareTransfer(
            String bearerToken,
            String idempotencyKey,
            UUID receiverUserId,
            long amountCent,
            String remark);

    Map<String, Object> transferOrder(String bearerToken, UUID transferId);

    List<Map<String, Object>> searchNearbyStores(
            String bearerToken, UUID locationContextId, UUID addressRefId, String fulfillmentType);

    List<Map<String, Object>> getStoreMenu(String bearerToken, UUID storeRefId);

    Map<String, Object> getFoodCart(String bearerToken, UUID storeRefId);

    Map<String, Object> updateFoodCart(
            String bearerToken,
            UUID storeRefId,
            UUID skuRefId,
            int quantity,
            Long expectedCartVersion);

    List<Map<String, Object>> listFoodAddresses(String bearerToken);

    Map<String, Object> prepareFoodCheckout(
            String bearerToken,
            UUID storeRefId,
            UUID addressRefId,
            String fulfillmentType);

    Map<String, Object> getFoodOrder(String bearerToken, UUID orderRefId);

    Map<String, Object> prepareFoodCancellation(String bearerToken, UUID orderRefId);
}
