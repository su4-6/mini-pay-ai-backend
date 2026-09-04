package com.minipay.commerce.application;

import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.CatalogView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceCatalogService {
    private final CommerceRepository repository;

    public CommerceCatalogService(CommerceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CatalogView.Merchant> search(
            String zoneCode,
            String categoryCode,
            Long maxDeliveryFeeCent,
            Integer maxDeliveryMinutes,
            int requestedLimit) {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw new CommerceApplicationException("COMMERCE_ZONE_REQUIRED", "请选择配送地址");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return repository.searchMerchants(
                zoneCode, categoryCode, maxDeliveryFeeCent, maxDeliveryMinutes, limit);
    }

    @Transactional(readOnly = true)
    public List<CatalogView.MenuItem> menu(UUID merchantId) {
        List<CatalogView.MenuItem> items = repository.menu(merchantId);
        if (items.isEmpty()) {
            throw new CommerceApplicationException("COMMERCE_MENU_NOT_FOUND", "商家菜单不存在或已下架");
        }
        return items;
    }
}
