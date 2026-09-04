package com.minipay.wallet.domain.model;

import java.util.List;

public record BillPage(List<WalletBill> items, int page, int size, long total) {
}
