package com.minipay.wallet.domain.model;

import java.util.List;

public record BillManagement(
        String categoryCode,
        List<BillTag> tags,
        String userNote,
        boolean includedInStatistics) {
}
