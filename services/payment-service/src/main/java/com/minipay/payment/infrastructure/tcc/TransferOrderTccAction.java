package com.minipay.payment.infrastructure.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface TransferOrderTccAction {

    @TwoPhaseBusinessAction(
            name = "transferOrderTccAction",
            commitMethod = "confirm",
            rollbackMethod = "cancel",
            useTCCFence = true)
    boolean tryCreate(
            BusinessActionContext context,
            @BusinessActionContextParameter(paramName = "transferNo") String transferNo);

    boolean confirm(BusinessActionContext context);

    boolean cancel(BusinessActionContext context);
}

