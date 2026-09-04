package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.port.MerchantStore.MerchantPage;
import com.minipay.payment.application.service.MerchantManagementService;
import com.minipay.payment.application.service.OpsOrderQueryService;
import com.minipay.payment.domain.model.MerchantStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only payment projections for the isolated system administrator portal. */
@Validated @RestController @RequestMapping("/api/v1/admin")
public class AdminPaymentController {
    private final MerchantManagementService merchants; private final OpsOrderQueryService orders;
    public AdminPaymentController(MerchantManagementService merchants,OpsOrderQueryService orders){this.merchants=merchants;this.orders=orders;}
    @GetMapping("/merchants") public AdminMerchantPage merchants(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,@RequestParam(required=false)String merchantNo,@RequestParam(required=false)String name,@RequestParam(required=false)MerchantStatus status){MerchantPage result=merchants.list(page,size,merchantNo,name,null,status);return new AdminMerchantPage(result.items().stream().map(AdminPaymentController::merchantView).toList(),result.page(),result.size(),result.total());}
    @GetMapping("/merchants/{id}") public AdminMerchantView merchant(@PathVariable UUID id){return merchantView(merchants.get(id));}
    @GetMapping("/orders/payments") public Object payments(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)UUID merchantId,@RequestParam(required=false)String merchantNo,@RequestParam(required=false)String name,@RequestParam(required=false)String appId,@RequestParam(required=false)String status,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return orders.payments(page,Math.max(1,Math.min(100,size)),merchantId,merchantNo,name,appId,status,from,to);}
    @GetMapping("/orders/payments/{no}") public Object payment(@PathVariable String no){return orders.payment(no);}
    @GetMapping("/orders/refunds") public Object refunds(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)UUID merchantId,@RequestParam(required=false)String merchantNo,@RequestParam(required=false)String name,@RequestParam(required=false)String status,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return orders.refunds(page,Math.max(1,Math.min(100,size)),merchantId,merchantNo,name,status,from,to);}
    @GetMapping("/orders/refunds/{no}") public Object refund(@PathVariable String no){return orders.refund(no);}
    @GetMapping("/orders/transfers") public Object transfers(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String transferNo,@RequestParam(required=false)String status,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return orders.transfers(page,Math.max(1,Math.min(100,size)),transferNo,status,from,to);}
    @GetMapping("/orders/transfers/{no}") public Object transfer(@PathVariable String no){return orders.transfer(no);}
    @GetMapping("/orders/recharges") public Object recharges(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String rechargeNo,@RequestParam(required=false)String status,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return orders.recharges(page,Math.max(1,Math.min(100,size)),rechargeNo,status,from,to);}
    @GetMapping("/orders/recharges/{no}") public Object recharge(@PathVariable String no){return orders.recharge(no);}
    @GetMapping("/orders/withdrawals") public Object withdrawals(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String withdrawalNo,@RequestParam(required=false)String status,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return orders.withdrawals(page,Math.max(1,Math.min(100,size)),withdrawalNo,status,from,to);}
    @GetMapping("/orders/withdrawals/{no}") public Object withdrawal(@PathVariable String no){return orders.withdrawal(no);}
    @GetMapping("/bank-cards") public Object bankCards(@RequestParam UUID userId){return orders.bankCards(userId);}
    private static AdminMerchantView merchantView(com.minipay.payment.application.port.MerchantStore.MerchantView item){return new AdminMerchantView(item.merchantId(),item.merchantNo(),item.name(),item.shortName(),item.status(),item.ownerUserId(),item.applicationCount(),item.createdAt(),item.updatedAt());}
    public record AdminMerchantView(UUID merchantId,String merchantNo,String name,String shortName,MerchantStatus status,UUID ownerUserId,long applicationCount,Instant createdAt,Instant updatedAt){}
    public record AdminMerchantPage(List<AdminMerchantView> items,int page,int size,long total){}
}
