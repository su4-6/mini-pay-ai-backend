package com.minipay.wallet.infrastructure.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface DebitAccountTccAction {

    @TwoPhaseBusinessAction(
            name = "walletTransferDebitTccV1",
            commitMethod = "confirm",
            rollbackMethod = "cancel",
            useTCCFence = true)
    boolean tryDebit(
            BusinessActionContext context,
            @BusinessActionContextParameter(paramName = "businessNo") String businessNo,
            @BusinessActionContextParameter(paramName = "source") String source,
            @BusinessActionContextParameter(paramName = "ownerId") String ownerId,
            @BusinessActionContextParameter(paramName = "accountId") String accountId,
            @BusinessActionContextParameter(paramName = "counterpartyOwnerId") String counterpartyOwnerId,
            @BusinessActionContextParameter(paramName = "amountCent") long amountCent,
            @BusinessActionContextParameter(paramName = "annualLimitMode") String annualLimitMode);

    boolean confirm(BusinessActionContext context);

    boolean cancel(BusinessActionContext context);
}
