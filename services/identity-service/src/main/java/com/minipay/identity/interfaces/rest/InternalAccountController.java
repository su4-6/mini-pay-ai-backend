package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.MerchantOwnerAccountService;
import com.minipay.identity.application.service.MerchantOwnerAccountService.ResolvedMerchantOwner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅内网访问（audience identity-internal + scope identity.account.manage）。
 * 供 payment-service 在 BD 代建 / 入驻审核通过时解析商户拥有者账户。
 */
@RestController
@RequestMapping
public class InternalAccountController {
    private final MerchantOwnerAccountService accounts;

    public InternalAccountController(MerchantOwnerAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/internal/v1/accounts/merchant-owner")
    public ResolvedMerchantOwner resolveMerchantOwner(
            @Valid @RequestBody MerchantOwnerRequest request) {
        return accounts.resolve(
                request.mobile(), request.displayName(), request.requestId());
    }

    public record MerchantOwnerRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @Size(max = 64) String displayName,
            @NotBlank @Size(max = 128) String requestId) {
    }
}
