package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.junit.jupiter.api.Test;

class SeataTransferCoordinatorContractTest {
    @Test
    void transferUsesTheVersionedTenSecondGlobalTransaction() throws Exception {
        GlobalTransactional annotation = SeataTransferCoordinator.class
                .getMethod("execute", UUID.class, String.class, String.class)
                .getAnnotation(GlobalTransactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("wallet-transfer-v1");
        assertThat(annotation.timeoutMills()).isEqualTo(10_000);
    }
}
