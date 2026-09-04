package com.minipay.wallet.infrastructure.tcc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;
import org.junit.jupiter.api.Test;

class WalletTccContractTest {
    @Test
    void debitAndCreditActionsUseStableNamesAndSeataFence() {
        assertAction(DebitAccountTccAction.class, "tryDebit", "walletTransferDebitTccV1");
        assertAction(CreditAccountTccAction.class, "tryCredit", "walletTransferCreditTccV1");
    }

    private void assertAction(Class<?> type, String methodName, String actionName) {
        TwoPhaseBusinessAction annotation = Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst().orElseThrow()
                .getAnnotation(TwoPhaseBusinessAction.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo(actionName);
        assertThat(annotation.useTCCFence()).isTrue();
    }
}
