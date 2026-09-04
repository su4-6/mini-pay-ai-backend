package com.minipay.payment.domain.model;

import java.util.List;

public record FundingOrderPage(
        List<FundingOrderListItem> items, int page, int size, long total) {
}
