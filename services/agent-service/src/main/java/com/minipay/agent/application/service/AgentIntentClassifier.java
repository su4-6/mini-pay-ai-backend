package com.minipay.agent.application.service;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class AgentIntentClassifier {
    private static final Set<String> CONTROLLED_KEYWORDS = Set.of(
            "余额", "账单", "消费", "支出", "收入", "转账", "转给", "收款",
            "外卖", "点餐", "奶茶", "咖啡", "购物车", "订单", "配送", "退款", "取消");

    public Intent classify(String message) {
        return CONTROLLED_KEYWORDS.stream().anyMatch(message::contains)
                ? Intent.CONTROLLED_BUSINESS
                : Intent.GENERAL_CONVERSATION;
    }

    public enum Intent {
        GENERAL_CONVERSATION,
        CONTROLLED_BUSINESS
    }
}
