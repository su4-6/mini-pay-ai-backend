package com.minipay.wallet.infrastructure.tcc;

import com.minipay.wallet.application.service.WalletTransferTccService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountTccActionImpl implements CreditAccountTccAction {
    private final WalletTransferTccService service;
    private final Counter tryCounter;
    private final Counter confirmCounter;
    private final Counter cancelCounter;
    private final Timer tryTimer;
    private final Timer confirmTimer;
    private final Timer cancelTimer;

    public CreditAccountTccActionImpl(
            WalletTransferTccService service, MeterRegistry meters) {
        this.service = service;
        this.tryCounter = counter(meters, "credit", "try");
        this.confirmCounter = counter(meters, "credit", "confirm");
        this.cancelCounter = counter(meters, "credit", "cancel");
        this.tryTimer = timer(meters, "credit", "try");
        this.confirmTimer = timer(meters, "credit", "confirm");
        this.cancelTimer = timer(meters, "credit", "cancel");
    }

    @Override
    public boolean tryCredit(
            BusinessActionContext context,
            String businessNo,
            String source,
            String ownerId,
            String accountId,
            String counterpartyOwnerId,
            long amountCent) {
        tryCounter.increment();
        return tryTimer.record(() -> service.tryCredit(
                context.getXid(),
                context.getBranchId(),
                businessNo,
                source,
                UUID.fromString(ownerId),
                UUID.fromString(accountId),
                UUID.fromString(counterpartyOwnerId),
                amountCent));
    }

    @Override
    public boolean confirm(BusinessActionContext context) {
        confirmCounter.increment();
        return confirmTimer.record(
                () -> service.confirmCredit(context.getXid(), context.getBranchId()));
    }

    @Override
    public boolean cancel(BusinessActionContext context) {
        cancelCounter.increment();
        return cancelTimer.record(
                () -> service.cancelCredit(context.getXid(), context.getBranchId()));
    }

    private static Counter counter(MeterRegistry meters, String action, String phase) {
        return Counter.builder("minipay_wallet_tcc_calls")
                .tag("action", action).tag("phase", phase).register(meters);
    }

    private static Timer timer(MeterRegistry meters, String action, String phase) {
        return Timer.builder("minipay_wallet_tcc_latency")
                .tag("action", action).tag("phase", phase).register(meters);
    }
}
