package co.yixiang.yshop.module.minipay.controller.app;

import static co.yixiang.yshop.framework.common.pojo.CommonResult.success;
import static co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.CreateOrderRequest;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.OrderView;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.QuoteItemRequest;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.QuoteRequest;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.QuoteView;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodService;
import co.yixiang.yshop.module.minipay.service.MiniPayLocationService;
import co.yixiang.yshop.module.minipay.service.MiniPayAddressService;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.AddressLocationDraftView;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.CreatedAddressView;
import co.yixiang.yshop.module.minipay.service.MiniPayFoodModels.StoreView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/minipay/food")
public class MiniPayH5FoodController {
    private final MiniPayFoodService food;
    private final MiniPayLocationService locations;
    private final MiniPayAddressService addresses;

    public MiniPayH5FoodController(
            MiniPayFoodService food,
            MiniPayLocationService locations,
            MiniPayAddressService addresses) {
        this.food = food;
        this.locations = locations;
        this.addresses = addresses;
    }

    @PostMapping("/stores/nearby")
    public CommonResult<List<StoreView>> stores(@Valid @RequestBody NearbyStoresRequest request) {
        String subject = food.subjectForMember(getLoginUserId());
        MiniPayLocationService.requireExactlyOneSource(
                request.locationContextId(), request.addressId());
        if (request.addressId() != null) {
            return success(food.nearbyStoresForAddress(
                    subject, request.addressId(), request.fulfillmentType()));
        }
        double serverRadius = "PICKUP".equalsIgnoreCase(request.fulfillmentType()) ? 10.0 : 50.0;
        return success(locations.nearby(
                subject, request.locationContextId(), request.fulfillmentType(), serverRadius));
    }

    @PostMapping("/address-location-drafts")
    public CommonResult<AddressLocationDraftView> addressLocationDraft(
            @Valid @RequestBody AddressLocationDraftRequest request) {
        long memberId = getLoginUserId();
        String subject = food.subjectForMember(memberId);
        return success(addresses.createDraft(memberId, subject, request.locationContextId()));
    }

    @PostMapping("/addresses")
    public CommonResult<CreatedAddressView> address(
            @RequestHeader("Idempotency-Key") @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateAddressRequest request) {
        long memberId = getLoginUserId();
        String subject = food.subjectForMember(memberId);
        return success(addresses.createAddress(memberId, subject, request.draftId(),
                request.recipient(), request.phone(), request.detail(),
                request.defaultAddress(), idempotencyKey));
    }

    @PostMapping("/checkout-quotes")
    public CommonResult<QuoteView> quote(@Valid @RequestBody QuoteH5Request request) {
        String subject = food.subjectForMember(getLoginUserId());
        return success(food.createQuote(new QuoteRequest(
                subject, request.shopId(), request.addressId(), request.fulfillmentType(),
                request.items())));
    }

    @PostMapping("/orders")
    public CommonResult<OrderView> order(
            @RequestHeader("Idempotency-Key") @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateH5OrderRequest request) {
        String subject = food.subjectForMember(getLoginUserId());
        return success(food.createOrder(
                new CreateOrderRequest(subject, request.quoteId(), request.remark()),
                idempotencyKey));
    }

    public record QuoteH5Request(
            @NotNull Long shopId,
            Long addressId,
            @NotNull String fulfillmentType,
            @NotEmpty List<QuoteItemRequest> items) { }

    public record CreateH5OrderRequest(@NotNull String quoteId, String remark) { }

    public record NearbyStoresRequest(
            String locationContextId,
            Long addressId,
            @NotNull String fulfillmentType) { }

    public record AddressLocationDraftRequest(@NotBlank String locationContextId) { }

    public record CreateAddressRequest(
            @NotBlank String draftId,
            @NotBlank @Size(max = 32) String recipient,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
            @NotBlank @Size(max = 256) String detail,
            boolean defaultAddress) { }
}
