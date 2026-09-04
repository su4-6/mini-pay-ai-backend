package com.minipay.commerce.application;

import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.CartSnapshot;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceCartService {
    private final CommerceRepository repository;
    private final Clock clock;

    @Autowired
    public CommerceCartService(CommerceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CommerceCartService(CommerceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public CartSnapshot update(
            UUID userId,
            UUID merchantId,
            UUID skuId,
            int quantity,
            Set<UUID> optionIds,
            Long expectedVersion) {
        if (quantity < 0 || quantity > 99) {
            throw new CommerceApplicationException(
                    "COMMERCE_CART_QUANTITY_INVALID", "商品数量必须在 0 到 99 之间");
        }
        return repository.updateCart(
                userId, merchantId, skuId, quantity,
                optionIds == null ? Set.of() : optionIds,
                expectedVersion, clock.instant());
    }

    @Transactional(readOnly = true)
    public CartSnapshot get(UUID userId, UUID merchantId) {
        return repository.findCart(userId, merchantId)
                .orElseThrow(() -> new CommerceApplicationException(
                        "COMMERCE_CART_NOT_FOUND", "购物车为空"));
    }
}
