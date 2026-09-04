package com.minipay.identity.application.service;

import com.minipay.identity.infrastructure.persistence.MerchantOwnerAccountRepository;
import com.minipay.identity.infrastructure.persistence.MerchantOwnerAccountRepository.MerchantOwnerAccount;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商户拥有者账户解析：按手机号查找已有账户并追加 merchant_owner 角色；
 * 不存在则创建一个仅短信登录（SMS_ONLY）的账户。find-or-create 幂等，
 * 供 BD 代建与入驻审核重复调用。
 */
@Service
public class MerchantOwnerAccountService {
    private final MerchantOwnerAccountRepository accounts;
    private final PhoneNumberService phones;

    public MerchantOwnerAccountService(
            MerchantOwnerAccountRepository accounts, PhoneNumberService phones) {
        this.accounts = accounts;
        this.phones = phones;
    }

    @Transactional
    public ResolvedMerchantOwner resolve(
            String mobile, String displayName, String requestId) {
        String normalized = phones.normalize(mobile);
        byte[] phoneHash = phones.hash(normalized);
        MerchantOwnerAccount existing = accounts.findByPhoneHash(phoneHash).orElse(null);
        if (existing != null) {
            if (!existing.active()) {
                throw new MerchantOwnerRejectedException("ACCOUNT_DISABLED");
            }
            accounts.addMerchantOwnerRole(existing.userId());
            return new ResolvedMerchantOwner(
                    existing.userId(), existing.loginName(), false);
        }
        MerchantOwnerAccount created =
                accounts.createSmsOnly(phoneHash, displayName, requestId);
        accounts.addMerchantOwnerRole(created.userId());
        return new ResolvedMerchantOwner(created.userId(), created.loginName(), true);
    }

    public record ResolvedMerchantOwner(UUID userId, String loginName, boolean newlyCreated) {
    }
}
