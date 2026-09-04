package com.minipay.agent.application.service;

import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.port.DelegatedTokenProvider;
import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentBusinessOrchestrator {
    private static final Pattern AMOUNT = Pattern.compile("(?<!\\d)(\\d{1,7}(?:\\.\\d{1,2})?)\\s*元");
    private static final Pattern RECIPIENT_ALIAS = Pattern.compile(
            "(?:转账给|转给|给)\\s*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,19}?)(?:转账)?(?=\\s*(?:转|\\d|$))");
    private static final Pattern LEADING_RECIPIENT_WITH_AMOUNT = Pattern.compile(
            "^\\s*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,19})\\s+(?=\\d)");
    private static final Pattern BARE_RECIPIENT_ALIAS = Pattern.compile("[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,19}");
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PENDING_TRANSFER_REMINDER =
            "\n\n另外，刚才的转账还未完成，需要时可以说“继续转账”。";
    private final AgentRunApplicationService runs;
    private final DelegatedAuthorizationService authorization;
    private final AgentBusinessGateway gateway;
    private final AgentTaskStateStore taskStates;
    private final String sandboxZone;

    public AgentBusinessOrchestrator(
            AgentRunApplicationService runs,
            DelegatedAuthorizationService authorization,
            AgentBusinessGateway gateway,
            AgentTaskStateStore taskStates,
            @Value("${minipay.agent.sandbox-zone:CN-SH-PD}") String sandboxZone) {
        this.runs = runs;
        this.authorization = authorization;
        this.gateway = gateway;
        this.taskStates = taskStates;
        this.sandboxZone = sandboxZone;
    }

    public HandlingOutcome handle(
            UUID userId,
            UUID runId,
            String message,
            String exactMobile,
            String androidAccessToken) {
        return handle(userId, runId, message, exactMobile, null, androidAccessToken, null);
    }

    public HandlingOutcome handle(
            UUID userId,
            UUID runId,
            String message,
            String exactMobile,
            String androidAccessToken,
            ModelGateway modelGateway) {
        return handle(userId, runId, message, exactMobile, null, androidAccessToken, modelGateway);
    }

    public HandlingOutcome handle(
            UUID userId,
            UUID runId,
            String message,
            String exactMobile,
            UUID locationContextId,
            String androidAccessToken) {
        return handle(userId, runId, message, exactMobile, locationContextId, androidAccessToken, null);
    }

    public HandlingOutcome handle(
            UUID userId,
            UUID runId,
            String message,
            String exactMobile,
            UUID locationContextId,
            String androidAccessToken,
            ModelGateway modelGateway) {
        boolean transferCommand = message.contains("转给")
                || (message.contains("转账") && !message.contains("记录") && !message.contains("账单"))
                || ((message.contains("转") || AMOUNT.matcher(message).find())
                    && (RECIPIENT_ALIAS.matcher(message).find()
                        || LEADING_RECIPIENT_WITH_AMOUNT.matcher(message).find()));
        UUID conversationId = runs.getRun(userId, runId).conversationId();
        Optional<AgentTaskStateStore.PendingTask> pending = pendingTransfer(userId, conversationId);
        PendingTransferRoute pendingRoute = routePendingTransferTurn(
                message, exactMobile, pending, modelGateway);
        if (pendingRoute == PendingTransferRoute.CANCEL) {
            taskStates.clear(userId, conversationId, "transfer");
            runs.completeBusinessReplyRun(
                    userId, runId, "AGENT_TRANSFER_CANCELLED", "已取消刚才未完成的转账。");
            return HandlingOutcome.HANDLED;
        }
        if (transferCommand || pendingRoute == PendingTransferRoute.CONTINUE) {
            transfer(userId, runId, message, exactMobile, androidAccessToken);
            return HandlingOutcome.HANDLED;
        }
        boolean pausedTransfer = pendingRoute == PendingTransferRoute.PAUSE;
        if (message.contains("余额")) {
            UUID traceId = toolStart(userId, runId, "wallet.getSummary", "R0");
            Map<String, Object> result = gateway.walletSummary(token(
                    androidAccessToken, runId, "wallet.getSummary"));
            toolDone(userId, runId, traceId, "wallet.getSummary");
            runs.completeStructuredRun(userId, runId, "wallet.card", "wallet.summary", result,
                    withPendingReminder("这是你的 MiniPay 沙箱钱包余额。", pausedTransfer));
            return HandlingOutcome.HANDLED;
        }
        boolean spendAmountQuestion = isSpendAmountQuestion(message);
        if (message.contains("账单") || message.contains("交易记录") || message.contains("最近几笔")
                || message.contains("支出") || message.contains("收入") || message.contains("消费")
                || message.contains("收支") || spendAmountQuestion) {
            Instant now = Instant.now();
            Instant[] range = billRange(message, now);
            String direction = message.contains("支出") || message.contains("消费")
                    || spendAmountQuestion ? "EXPENSE"
                    : message.contains("收入") ? "INCOME" : null;
            String businessType = message.contains("转账") ? "TRANSFER"
                    : message.contains("退款") ? "REFUND" : null;
            String status = message.contains("失败") ? "FAILED"
                    : message.contains("处理中") ? "PROCESSING" : null;
            boolean aggregate = message.contains("分析") || message.contains("统计")
                    || message.contains("汇总") || message.contains("收支") || spendAmountQuestion;
            if (aggregate) {
                UUID traceId = toolStart(userId, runId, "wallet.aggregateBills", "R0");
                Map<String, Object> result = gateway.aggregateBills(token(
                        androidAccessToken, runId, "wallet.aggregateBills"), range[0], range[1]);
                toolDone(userId, runId, traceId, "wallet.aggregateBills");
                runs.completeStructuredRun(userId, runId, "bill.summary.card", "wallet.bill-summary",
                        result, withPendingReminder(
                                "这是根据钱包权威账单生成的收支汇总。", pausedTransfer));
            } else {
                UUID traceId = toolStart(userId, runId, "wallet.listBills", "R0");
                Map<String, Object> result = gateway.listBills(token(
                        androidAccessToken, runId, "wallet.listBills"), range[0], range[1],
                        direction, businessType, status, 1, 20);
                toolDone(userId, runId, traceId, "wallet.listBills");
                Map<String, Object> card = new LinkedHashMap<>(result);
                card.put("from", range[0].toString());
                card.put("to", range[1].toString());
                runs.completeStructuredRun(userId, runId, "bills.card", "wallet.bill-list",
                        card, withPendingReminder(
                                "这是 Wallet 返回的权威交易记录。", pausedTransfer));
            }
            return HandlingOutcome.HANDLED;
        }
        if (message.contains("外卖") || message.contains("点餐") || message.contains("奶茶")
                || message.contains("咖啡")) {
            runs.completeStructuredRun(userId, runId, "food.entry.card", "commerce.food-entry",
                    Map.of("service", "food", "destination", "FOOD_ENTRY"),
                    withPendingReminder(
                            "已为你准备好意向外卖入口，点击卡片开始点餐。",
                            pausedTransfer));
            return HandlingOutcome.HANDLED;
        }
        return new HandlingOutcome(false, pausedTransfer);
    }

    public void continueAction(
            UUID userId,
            UUID runId,
            ActionCommand command,
            String androidAccessToken) {
        runs.resumeExecution(userId, runId);
        switch (command.action()) {
            case "SEARCH_STORES_BY_ADDRESS" ->
                    searchStoresByAddress(userId, runId, command, androidAccessToken);
            case "GET_MENU" -> getMenu(userId, runId, command, androidAccessToken);
            case "GET_CART" -> getCart(userId, runId, command, androidAccessToken);
            case "UPDATE_CART" -> updateCart(userId, runId, command, androidAccessToken);
            case "GET_ADDRESSES" -> getAddresses(userId, runId, command, androidAccessToken);
            case "PREPARE_CHECKOUT" -> prepareCheckout(userId, runId, command, androidAccessToken);
            case "GET_ORDER" -> getOrder(userId, runId, command, androidAccessToken);
            case "GET_TRANSFER" -> getTransfer(userId, runId, command, androidAccessToken);
            case "COMPLETE_NATIVE_TRANSFER" -> completeNativeTransfer(userId, runId, command);
            case "SELECT_TRANSFER_RECIPIENT" ->
                    selectTransferRecipient(userId, runId, command, androidAccessToken);
            case "PREPARE_CANCELLATION", "PREPARE_REFUND" ->
                    prepareCancellation(userId, runId, command, androidAccessToken);
            default -> throw new AgentApplicationException(
                    "AGENT_ACTION_NOT_SUPPORTED", "该卡片操作未开放");
        }
    }

    private void searchStoresByAddress(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.addressId(), "addressId");
        UUID trace = toolStart(userId, runId, "commerce.searchNearbyStores", "R0");
        List<Map<String, Object>> merchants = gateway.searchNearbyStores(token(
                subjectToken, runId, "commerce.searchNearbyStores"),
                null, command.addressId(), "TAKEOUT");
        toolDone(userId, runId, trace, "commerce.searchNearbyStores");
        runs.waitForInputCard(userId, runId, "merchant.card", "commerce.merchants",
                Map.of("addressRefId", command.addressId(), "items", merchants),
                merchants.isEmpty() ? "该地址附近暂无可配送门店。" : "已按所选地址查询附近门店。");
    }

    private void getMenu(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.merchantId(), "merchantId");
        UUID trace = toolStart(userId, runId, "commerce.getStoreMenu", "R0");
        List<Map<String, Object>> menu = gateway.getStoreMenu(token(
                subjectToken, runId, "commerce.getStoreMenu"), command.merchantId());
        toolDone(userId, runId, trace, "commerce.getStoreMenu");
        runs.waitForInputCard(userId, runId, "product.card", "commerce.menu",
                Map.of("storeRefId", command.merchantId(), "items", menu),
                "请选择商品和规格。");
    }

    private void getCart(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.merchantId(), "merchantId");
        UUID trace = toolStart(userId, runId, "commerce.getFoodCart", "R0");
        Map<String, Object> cart = gateway.getFoodCart(token(
                subjectToken, runId, "commerce.getFoodCart"), command.merchantId());
        toolDone(userId, runId, trace, "commerce.getFoodCart");
        runs.waitForInputCard(userId, runId, "cart.card", "commerce.cart", cart,
                "这是当前 AI 点餐购物车。");
    }

    private void updateCart(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.merchantId(), "merchantId");
        require(command.skuId(), "skuId");
        if (command.quantity() == null || command.quantity() < 0 || command.quantity() > 99) {
            throw new AgentApplicationException("AGENT_CART_QUANTITY_INVALID", "商品数量无效");
        }
        UUID trace = toolStart(userId, runId, "commerce.updateFoodCart", "W1");
        Map<String, Object> cart = gateway.updateFoodCart(token(
                        subjectToken, runId, "commerce.updateFoodCart"),
                command.merchantId(), command.skuId(), command.quantity(),
                command.expectedCartVersion());
        toolDone(userId, runId, trace, "commerce.updateFoodCart");
        runs.waitForInputCard(userId, runId, "cart.card", "commerce.cart", cart,
                "购物车已按你的选择更新，请继续选购或去结算。");
    }

    private void getAddresses(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.merchantId(), "merchantId");
        UUID trace = toolStart(userId, runId, "commerce.listFoodAddresses", "R0");
        List<Map<String, Object>> addresses = gateway.listFoodAddresses(token(
                subjectToken, runId, "commerce.listFoodAddresses"));
        toolDone(userId, runId, trace, "commerce.listFoodAddresses");
        runs.waitForInputCard(userId, runId, "address.card", "commerce.addresses",
                Map.of("storeRefId", command.merchantId(), "items", addresses),
                "请选择一个脱敏地址，或改为到店自取。");
    }

    private void prepareCheckout(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.merchantId(), "merchantId");
        String fulfillmentType = command.fulfillmentType() == null
                ? "TAKEOUT" : command.fulfillmentType().strip().toUpperCase(java.util.Locale.ROOT);
        if (!"TAKEOUT".equals(fulfillmentType) && !"PICKUP".equals(fulfillmentType)) {
            throw new AgentApplicationException(
                    "AGENT_FULFILLMENT_TYPE_INVALID", "履约方式必须为外卖或到店自取");
        }
        if ("TAKEOUT".equals(fulfillmentType)) {
            require(command.addressId(), "addressId");
        }
        UUID trace = toolStart(userId, runId, "commerce.prepareFoodCheckout", "W1");
        Map<String, Object> quote = gateway.prepareFoodCheckout(token(
                        subjectToken, runId, "commerce.prepareFoodCheckout"),
                command.merchantId(), command.addressId(), fulfillmentType);
        toolDone(userId, runId, trace, "commerce.prepareFoodCheckout");
        Map<String, Object> card = new LinkedHashMap<>(quote);
        card.put("confirmationRequired", true);
        runs.waitForConfirmation(userId, runId, "checkout.card", "commerce.checkout", card,
                "请核对商品、配送地址摘要和实付金额，然后进入原生付款页。");
    }

    private void getOrder(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.orderId(), "orderId");
        UUID trace = toolStart(userId, runId, "commerce.getFoodOrder", "R0");
        Map<String, Object> order = gateway.getFoodOrder(token(
                subjectToken, runId, "commerce.getFoodOrder"), command.orderId());
        toolDone(userId, runId, trace, "commerce.getFoodOrder");
        runs.completeStructuredRun(userId, runId, "order.card", "commerce.order", order,
                "这是订单的权威履约状态。");
    }

    private void getTransfer(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.transferId(), "transferId");
        UUID trace = toolStart(userId, runId, "payment.getTransfer", "R0");
        Map<String, Object> order = gateway.transferOrder(token(
                subjectToken, runId, "payment.getTransfer"), command.transferId());
        toolDone(userId, runId, trace, "payment.getTransfer");
        if ("PROCESSING".equals(String.valueOf(order.get("status")))) {
            runs.waitForInputCard(userId, runId, "transfer.result.card",
                    "payment.transfer-order", order,
                    "转账正在处理中，可稍后刷新权威状态。");
        } else {
            runs.completeStructuredRun(userId, runId, "transfer.result.card",
                    "payment.transfer-order", order,
                    "这是 Payment 返回的权威转账结果。");
        }
    }

    private void completeNativeTransfer(UUID userId, UUID runId, ActionCommand command) {
        require(command.transferId(), "transferId");
        runs.completeSilentRun(userId, runId);
    }

    private void prepareCancellation(
            UUID userId, UUID runId, ActionCommand command, String subjectToken) {
        require(command.orderId(), "orderId");
        String tool = "commerce.prepareFoodCancellation";
        UUID trace = toolStart(userId, runId, tool, "W1");
        Map<String, Object> preview = gateway.prepareFoodCancellation(token(
                subjectToken, runId, tool), command.orderId());
        toolDone(userId, runId, trace, tool);
        runs.waitForConfirmation(userId, runId, "cancellation.card",
                "commerce.cancellation-preview", preview,
                "请核对取消或全额退款信息，再点击原生确认按钮。");
    }

    private static void require(Object value, String field) {
        if (value == null) throw new AgentApplicationException(
                "AGENT_ACTION_PARAMETER_MISSING", field + " 缺失");
    }

    private void transfer(
            UUID userId,
            UUID runId,
            String message,
            String exactMobile,
            String androidAccessToken) {
        UUID conversationId = runs.getRun(userId, runId).conversationId();
        Map<String, Object> slots = taskStates.findLatest(userId, conversationId,
                        Instant.now().minus(Duration.ofMinutes(30)))
                .filter(value -> "transfer".equals(value.taskType()))
                .map(AgentTaskStateStore.PendingTask::slots)
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
        Long amountCent = amountCent(message);
        if (amountCent == null && slots.get("amountCent") instanceof Number value) {
            amountCent = value.longValue();
        }
        String alias = exactMobile == null ? recipientAlias(message) : null;
        if (exactMobile == null && alias == null) {
            Matcher leading = LEADING_RECIPIENT_WITH_AMOUNT.matcher(message);
            if (leading.find()) alias = leading.group(1).strip();
        }
        if (exactMobile == null && alias == null && !message.contains("转账")
                && slots.get("recipientUserId") == null
                && BARE_RECIPIENT_ALIAS.matcher(message.strip()).matches()) {
            alias = message.strip();
        }
        Map<String, Object> recipient = null;
        if (exactMobile != null) {
            UUID recipientTrace = toolStart(userId, runId, "recipient.resolveExactMobile", "R0");
            recipient = gateway.resolveRecipient(token(
                    androidAccessToken, runId, "recipient.resolveExactMobile"), exactMobile);
            toolDone(userId, runId, recipientTrace, "recipient.resolveExactMobile");
        } else if (alias != null) {
            UUID contactTrace = toolStart(userId, runId, "contact.resolveExactFriend", "R0");
            List<Map<String, Object>> matches = gateway.resolveExactFriend(token(
                    androidAccessToken, runId, "contact.resolveExactFriend"), alias);
            toolDone(userId, runId, contactTrace, "contact.resolveExactFriend");
            if (matches.isEmpty()) {
                throw new AgentApplicationException(
                        "AGENT_CONTACT_NOT_FOUND", "未在好友中找到该收款人，请核对昵称、完整实名或改用完整手机号");
            }
            if (matches.size() > 1) {
                slots.put("recipientCandidates", matches);
                if (amountCent != null) slots.put("amountCent", amountCent);
                slots.put("awaitingFields", List.of("recipientSelection"));
                slots.put("lastPromptType", "RECIPIENT_SELECTION");
                taskStates.save(runId, "transfer", slots, Instant.now());
                runs.waitForInputCard(userId, runId, "choice.card", "agent.contact-selection",
                        Map.of("items", matches), "找到多位匹配的好友，请选择收款人。");
                return;
            }
            recipient = new LinkedHashMap<>(matches.getFirst());
        } else if (slots.get("recipientUserId") != null) {
            recipient = new LinkedHashMap<>();
            for (String key : List.of("recipientUserId", "nickname", "phoneMasked", "legalNameMasked", "verified")) {
                if (slots.containsKey(key)) recipient.put(key, slots.get(key));
            }
        }

        if (recipient != null) {
            slots.put("recipientUserId", recipient.get("recipientUserId"));
            slots.put("nickname", recipient.get("nickname"));
            slots.put("phoneMasked", recipient.get("phoneMasked"));
            slots.put("verified", recipient.get("verified"));
            if (recipient.get("legalNameMasked") != null) slots.put("legalNameMasked", recipient.get("legalNameMasked"));
        }
        if (amountCent != null) slots.put("amountCent", amountCent);

        if (recipient == null || amountCent == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            if (recipient == null) {
                missing.put("recipient", "请输入完整手机号、好友昵称或好友完整实名");
            }
            if (amountCent == null) missing.put("amount", "请输入转账金额，例如 20 元");
            String prompt;
            if (missing.containsKey("recipient") && missing.containsKey("amount")) {
                prompt = "请告诉我收款人的完整手机号、好友昵称或好友完整实名，以及转账金额，例如“转给小明 20 元”。";
            } else if (missing.containsKey("recipient")) {
                prompt = "请告诉我收款人的完整手机号、好友昵称或好友完整实名。";
            } else {
                prompt = "请告诉我转账金额，例如“20 元”。";
            }
            slots.put("awaitingFields", List.copyOf(missing.keySet()));
            slots.put("lastPromptType", missing.size() == 2
                    ? "RECIPIENT_AND_AMOUNT"
                    : missing.containsKey("recipient") ? "RECIPIENT" : "AMOUNT");
            taskStates.save(runId, "transfer", slots, Instant.now());
            runs.requestInput(userId, runId, "transfer", missing, prompt);
            return;
        }
        UUID receiverUserId = UUID.fromString(String.valueOf(recipient.get("recipientUserId")));
        UUID transferTrace = toolStart(userId, runId, "payment.prepareTransfer", "W1");
        Map<String, Object> intent = gateway.prepareTransfer(token(
                        androidAccessToken, runId, "payment.prepareTransfer"),
                runId + ":transfer", receiverUserId, amountCent, null);
        toolDoneForConfirmation(userId, runId, transferTrace, "payment.prepareTransfer");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("intent", intent);
        Map<String, Object> safeRecipient = new LinkedHashMap<>();
        safeRecipient.put("nickname", recipient.get("nickname"));
        safeRecipient.put("phoneMasked", recipient.get("phoneMasked"));
        safeRecipient.put("legalNameMasked", recipient.get("legalNameMasked"));
        safeRecipient.put("verified", recipient.get("verified"));
        card.put("recipient", safeRecipient);
        card.put("amountCent", amountCent);
        card.put("currency", "CNY");
        card.put("confirmationRequired", true);
        runs.waitForConfirmation(userId, runId, "transfer.card", "payment.transfer-intent", card,
                "请核对收款人和金额，然后在原生安全页面确认。");
        taskStates.clear(userId, conversationId, "transfer");
    }

    @SuppressWarnings("unchecked")
    private void selectTransferRecipient(
            UUID userId, UUID runId, ActionCommand command, String androidAccessToken) {
        if (command.recipientUserId() == null) {
            throw new AgentApplicationException("AGENT_RECIPIENT_SELECTION_REQUIRED", "请选择收款人");
        }
        Map<String, Object> currentSlots = taskStates.findForRun(userId, runId, "transfer")
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_RECIPIENT_SELECTION_EXPIRED", "收款人选择已失效，请重新发起转账"))
                .slots();
        Object rawCandidates = currentSlots.get("recipientCandidates");
        if (!(rawCandidates instanceof List<?> candidates)) {
            throw new AgentApplicationException("AGENT_RECIPIENT_SELECTION_INVALID", "收款人候选无效");
        }
        Map<String, Object> selected = candidates.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .filter(value -> command.recipientUserId().toString()
                        .equals(String.valueOf(value.get("recipientUserId"))))
                .findFirst()
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_RECIPIENT_SELECTION_INVALID", "所选收款人不在候选列表中"));
        Map<String, Object> slots = new LinkedHashMap<>(currentSlots);
        slots.remove("recipientCandidates");
        slots.remove("awaitingFields");
        slots.remove("lastPromptType");
        for (String key : List.of(
                "recipientUserId", "nickname", "phoneMasked", "legalNameMasked", "verified")) {
            if (selected.get(key) != null) slots.put(key, selected.get(key));
        }
        taskStates.save(runId, "transfer", slots, Instant.now());
        transfer(userId, runId, "", null, androidAccessToken);
    }

    private Optional<AgentTaskStateStore.PendingTask> pendingTransfer(
            UUID userId, UUID conversationId) {
        return taskStates.findLatest(
                        userId, conversationId, Instant.now().minus(Duration.ofMinutes(30)))
                .filter(value -> "transfer".equals(value.taskType()));
    }

    private PendingTransferRoute routePendingTransferTurn(
            String message,
            String exactMobile,
            Optional<AgentTaskStateStore.PendingTask> pending,
            ModelGateway modelGateway) {
        if (pending.isEmpty()) return PendingTransferRoute.NONE;
        String normalized = message.strip();
        if (isTransferCancellation(normalized)) return PendingTransferRoute.CANCEL;
        if (exactMobile != null || isTransferContinuation(normalized)) {
            return PendingTransferRoute.CONTINUE;
        }
        List<String> awaitingFields = awaitingFields(pending.get().slots());
        if (AMOUNT.matcher(normalized).find() && awaitingFields.contains("amount")) {
            return PendingTransferRoute.CONTINUE;
        }
        if (recipientAlias(normalized) != null
                || LEADING_RECIPIENT_WITH_AMOUNT.matcher(normalized).find()) {
            return PendingTransferRoute.CONTINUE;
        }
        if (isDifferentBusinessIntent(normalized)) return PendingTransferRoute.PAUSE;
        if (awaitingFields.contains("recipient")
                && BARE_RECIPIENT_ALIAS.matcher(normalized).matches()
                && modelGateway != null) {
            ModelGateway.PendingTransferTurn classification =
                    modelGateway.classifyPendingTransferTurn(
                            new ModelGateway.PendingTransferClassificationRequest(
                                    normalized, awaitingFields, "v1"));
            if (classification == ModelGateway.PendingTransferTurn.TRANSFER_SLOT) {
                return PendingTransferRoute.CONTINUE;
            }
        }
        return PendingTransferRoute.PAUSE;
    }

    private static List<String> awaitingFields(Map<String, Object> slots) {
        Object stored = slots.get("awaitingFields");
        if (stored instanceof List<?> values) {
            List<String> fields = values.stream().map(String::valueOf).toList();
            if (!fields.isEmpty()) return fields;
        }
        java.util.ArrayList<String> derived = new java.util.ArrayList<>(2);
        if (slots.get("recipientUserId") == null) derived.add("recipient");
        if (slots.get("amountCent") == null) derived.add("amount");
        return List.copyOf(derived);
    }

    private static boolean isTransferCancellation(String message) {
        return message.contains("取消转账") || message.contains("不转了")
                || message.contains("不要转账") || message.equals("算了")
                || message.contains("取消刚才");
    }

    private static boolean isTransferContinuation(String message) {
        return message.contains("继续转账") || message.contains("接着转")
                || message.contains("继续刚才的转账");
    }

    private static boolean isDifferentBusinessIntent(String message) {
        return message.contains("余额") || message.contains("账单") || message.contains("交易记录")
                || message.contains("支出") || message.contains("收入") || message.contains("收支")
                || isSpendAmountQuestion(message)
                || message.contains("外卖") || message.contains("点餐") || message.contains("奶茶")
                || message.contains("咖啡");
    }

    private static boolean isSpendAmountQuestion(String message) {
        return message.contains("花了多少") || message.contains("花费多少")
                || message.contains("花销") || message.contains("消费了多少")
                || message.contains("消费多少") || message.contains("用了多少钱")
                || message.contains("支出了多少");
    }

    private static String withPendingReminder(String text, boolean pausedTransfer) {
        return pausedTransfer ? text + PENDING_TRANSFER_REMINDER : text;
    }

    private String token(String subjectToken, UUID runId, String toolName) {
        DelegatedTokenProvider.DelegatedToken delegated =
                authorization.exchangeForTool(subjectToken, runId, toolName);
        return delegated.accessToken();
    }

    private UUID toolStart(UUID userId, UUID runId, String toolName, String riskLevel) {
        runs.transition(userId, runId, AgentRunStatus.EXECUTING_TOOL);
        return runs.startToolTrace(userId, runId, toolName, riskLevel);
    }

    private void toolDone(UUID userId, UUID runId, UUID traceId, String toolName) {
        runs.completeToolTrace(userId, runId, traceId, toolName, "SUCCEEDED");
        runs.transition(userId, runId, AgentRunStatus.UNDERSTANDING);
    }

    private void toolDoneForConfirmation(UUID userId, UUID runId, UUID traceId, String toolName) {
        runs.completeToolTrace(userId, runId, traceId, toolName, "SUCCEEDED");
    }

    private static Long amountCent(String message) {
        Matcher matcher = AMOUNT.matcher(message);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1)).movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException exception) {
            throw new AgentApplicationException("AGENT_AMOUNT_INVALID", "转账金额格式无效");
        }
    }

    private static Instant[] billRange(String message, Instant now) {
        var localNow = now.atZone(BILL_ZONE);
        Instant from;
        if (isTodayRequest(message)) {
            from = localNow.toLocalDate().atStartOfDay(BILL_ZONE).toInstant();
        } else if (message.contains("上月")) {
            var firstThisMonth = localNow.with(TemporalAdjusters.firstDayOfMonth())
                    .toLocalDate().atStartOfDay(BILL_ZONE);
            return new Instant[]{firstThisMonth.minusMonths(1).toInstant(), firstThisMonth.toInstant()};
        } else if (message.contains("本月")) {
            from = localNow.with(TemporalAdjusters.firstDayOfMonth())
                    .toLocalDate().atStartOfDay(BILL_ZONE).toInstant();
        } else if (message.contains("近7天") || message.contains("最近7天")) {
            from = now.minus(Duration.ofDays(7));
        } else {
            from = now.minus(Duration.ofDays(30));
        }
        return new Instant[]{from, now};
    }

    private static boolean isTodayRequest(String message) {
        return message.contains("今天") || message.contains("今日")
                || message.contains("本日") || message.contains("当天");
    }

    private static String recipientAlias(String message) {
        Matcher matcher = RECIPIENT_ALIAS.matcher(message);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    public record HandlingOutcome(boolean handled, boolean pendingTransferPaused) {
        public static final HandlingOutcome HANDLED = new HandlingOutcome(true, false);
    }

    private enum PendingTransferRoute {
        NONE,
        CONTINUE,
        PAUSE,
        CANCEL
    }

    public record ActionCommand(
            String action,
            UUID merchantId,
            UUID skuId,
            Integer quantity,
            List<UUID> optionIds,
            Long expectedCartVersion,
            UUID addressId,
            UUID orderId,
            UUID transferId,
            String fulfillmentType,
            UUID recipientUserId) {
        public ActionCommand(
                String action,
                UUID merchantId,
                UUID skuId,
                Integer quantity,
                List<UUID> optionIds,
                Long expectedCartVersion,
                UUID addressId,
                UUID orderId,
                UUID transferId) {
            this(action, merchantId, skuId, quantity, optionIds, expectedCartVersion,
                    addressId, orderId, transferId, null, null);
        }

        public ActionCommand(
                String action,
                UUID merchantId,
                UUID skuId,
                Integer quantity,
                List<UUID> optionIds,
                Long expectedCartVersion,
                UUID addressId,
                UUID orderId,
                UUID transferId,
                UUID recipientUserId) {
            this(action, merchantId, skuId, quantity, optionIds, expectedCartVersion,
                    addressId, orderId, transferId, null, recipientUserId);
        }

        public ActionCommand {
            optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
        }
    }
}
