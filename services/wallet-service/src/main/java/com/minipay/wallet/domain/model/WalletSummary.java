package com.minipay.wallet.domain.model;

import java.util.List;
import java.util.UUID;

public record WalletSummary(
        UUID walletId,
        long availableAmountCent,
        long frozenAmountCent,
        long totalAmountCent,
        String currency,
        String status,
        int annualOutflowYear,
        long annualOutflowLimitCent,
        long annualOutflowUsedCent,
        long annualOutflowRemainingCent,
        String sandboxNotice,
        List<WalletBill> recentBills) {
}
