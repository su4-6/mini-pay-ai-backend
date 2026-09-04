package com.minipay.wallet.domain.model;

import java.util.List;

public record BillTagPage(List<BillTag> items, int page, int size, long total) {
}
