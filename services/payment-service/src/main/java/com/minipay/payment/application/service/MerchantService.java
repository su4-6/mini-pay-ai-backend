package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.security.MerchantSecretCipher;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.CollectionCodeRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantApplicationRow;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.CreatedDefaultApplication;
import com.minipay.payment.infrastructure.persistence.MerchantRepository.MerchantRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CATEGORIES = Set.of("餐饮", "零售", "生活服务", "其他");
    private static final Set<String> CHANNELS = Set.of("WALLET", "ALIPAY", "WECHAT");
    private static final Set<String> APPLICATION_PERMISSIONS = Set.of(
            "PAYMENT_CREATE", "PAYMENT_QUERY", "REFUND_CREATE", "BILL_QUERY");
    private final MerchantRepository repository;
    private final MerchantSecretCipher secretCipher;
    private final MerchantContentSafety contentSafety;
    private final RefundService refunds;

    public MerchantService(MerchantRepository repository, MerchantSecretCipher secretCipher,
            MerchantContentSafety contentSafety, RefundService refunds) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.contentSafety = contentSafety;
        this.refunds = refunds;
    }

    public MerchantView current(UUID ownerUserId) {
        return repository.findByOwner(ownerUserId).map(MerchantView::from).orElse(null);
    }

    public List<MerchantView> merchants(UUID ownerUserId) {
        return repository.findAllByOwner(ownerUserId).stream().map(MerchantView::from).toList();
    }

    public MerchantView merchant(UUID ownerUserId, UUID merchantId) {
        return MerchantView.from(requireMerchant(ownerUserId, merchantId));
    }

    @Transactional
    public InitializationView initialize(UUID ownerUserId) {
        MerchantRow first = requireMerchant(ownerUserId);
        return initialize(ownerUserId, first.merchantId());
    }

    @Transactional
    public InitializationView initialize(UUID ownerUserId, UUID merchantId) {
        MerchantRow merchant = repository.findOwnedForUpdate(ownerUserId, merchantId)
                .orElseThrow(() -> problem("MERCHANT_NOT_FOUND", HttpStatus.NOT_FOUND));
        MerchantApplicationRow application = repository.findDefaultApplication(merchant.merchantId()).orElse(null);
        CollectionCodeRow code;
        boolean initializedNow = false;
        if (application == null) {
            requireWritable(merchant);
            MerchantSecretCipher.SecretMaterial secret = secretCipher.newSecret();
            CreatedDefaultApplication created = repository.createDefaultApplication(merchant.merchantId(), secret.ciphertext());
            application = created.application();
            code = created.collectionCode();
            initializedNow = true;
        } else {
            code = repository.findDefaultCode(merchant.merchantId()).orElse(null);
            if (code == null) {
                requireWritable(merchant);
                code = repository.createCollectionCode(application.applicationId());
                initializedNow = true;
            }
        }
        MerchantRow currentMerchant = repository.findOwned(ownerUserId, merchantId).orElseThrow();
        if (initializedNow) {
            repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "MERCHANT_INITIALIZED",
                    "MERCHANT", merchant.merchantNo(), null, null, null, application.appId());
        }
        CollectionCodeView collectionCode = code == null ? null : CollectionCodeView.from(code);
        return new InitializationView(MerchantView.from(currentMerchant), ApplicationView.from(application),
                collectionCode, collectionCode == null ? null : collectionCode.qrContent(), null);
    }

    public DashboardView dashboard(UUID ownerUserId, int days) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return dashboard(merchant, days);
    }

    public DashboardView dashboard(UUID ownerUserId, UUID merchantId, int days) {
        return dashboard(requireMerchant(ownerUserId, merchantId), days);
    }

    private DashboardView dashboard(MerchantRow merchant, int days) {
        int range = days == 30 ? 30 : 7;
        LocalDate today = LocalDate.now(SHANGHAI);
        List<MerchantRepository.DashboardRow> rows = repository.dashboard(merchant.merchantId(),
                today.minusDays(range - 1L), today);
        long paymentAmount = rows.stream().mapToLong(MerchantRepository.DashboardRow::paymentAmountCent).sum();
        long paymentCount = rows.stream().mapToLong(MerchantRepository.DashboardRow::paymentCount).sum();
        long refundAmount = rows.stream().mapToLong(MerchantRepository.DashboardRow::refundAmountCent).sum();
        long refundCount = rows.stream().mapToLong(MerchantRepository.DashboardRow::refundCount).sum();
        MerchantRepository.DashboardSummaryRow todaySummary = repository.dashboardSummary(merchant.merchantId(), today, today);
        MerchantRepository.DashboardSummaryRow cumulative = repository.dashboardSummary(merchant.merchantId(),
                LocalDate.of(2000, 1, 1), today);
        Instant dataAsOf = rows.stream().map(MerchantRepository.DashboardRow::updatedAt)
                .filter(value -> value != null).max(Instant::compareTo).orElse(null);
        return new DashboardView(range, paymentAmount, paymentCount, refundAmount, refundCount,
                todaySummary.paymentAmountCent(), todaySummary.paymentCount(), todaySummary.refundAmountCent(), todaySummary.refundCount(),
                cumulative.paymentAmountCent(), cumulative.paymentCount(), cumulative.refundAmountCent(), cumulative.refundCount(), dataAsOf,
                rows.stream().map(row -> new DailyMetricView(row.statDate(), row.paymentAmountCent(),
                        row.paymentCount(), row.refundAmountCent(), row.refundCount())).toList());
    }

    /** Merchant-facing order read view. Order creation, payment and refunds remain owned by the order module. */
    public MerchantOrderPage orders(UUID ownerUserId, UUID merchantId, int page, int size,
            String orderNo, String status, String channel, Instant from, Instant to) {
        MerchantRow merchant = requireMerchant(ownerUserId, merchantId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<MerchantOrderView> items = repository.orders(merchant.merchantId(), orderNo, status, channel,
                        from, to, safeSize, (safePage - 1) * safeSize).stream()
                .map(row -> new MerchantOrderView(row.paymentOrderNo(), row.merchantOrderNo(), row.appId(),
                        row.amountCent(), row.currency(), row.subject(), row.channel(), row.allowedChannels(),
                        row.status(), row.expiresAt(), row.createdAt(), row.updatedAt(), row.refundNo(),
                        row.refundAmountCent(), row.refundStatus(), row.refundReason())).toList();
        return new MerchantOrderPage(items, safePage, safeSize,
                repository.countOrders(merchant.merchantId(), orderNo, status, channel, from, to));
    }

    public List<ChannelDistributionView> channelDistribution(UUID ownerUserId) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return channelDistribution(merchant);
    }

    public List<ChannelDistributionView> channelDistribution(UUID ownerUserId, UUID merchantId) {
        return channelDistribution(requireMerchant(ownerUserId, merchantId));
    }

    private List<ChannelDistributionView> channelDistribution(MerchantRow merchant) {
        return repository.channelDistribution(merchant.merchantId()).stream()
                .map(row -> new ChannelDistributionView(row.appId(), row.channel(), row.orderCount(), row.amountCent())).toList();
    }

    /** Order-module projection, constrained by the current merchant before any detail or refund action. */
    public MerchantOrderDetailView order(UUID ownerUserId, String paymentOrderNo) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return order(merchant, paymentOrderNo);
    }

    public MerchantOrderDetailView order(UUID ownerUserId, UUID merchantId, String paymentOrderNo) {
        return order(requireMerchant(ownerUserId, merchantId), paymentOrderNo);
    }

    private MerchantOrderDetailView order(MerchantRow merchant, String paymentOrderNo) {
        return MerchantOrderDetailView.from(repository.findOrder(merchant.merchantId(), paymentOrderNo)
                .orElseThrow(() -> problem("MERCHANT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND)));
    }

    @Transactional
    public Refund refundOrder(UUID ownerUserId, String paymentOrderNo, String idempotencyKey, long amountCent, String reason) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return refundOrder(ownerUserId, merchant, paymentOrderNo, idempotencyKey, amountCent, reason);
    }

    @Transactional
    public Refund refundOrder(UUID ownerUserId, UUID merchantId, String paymentOrderNo,
            String idempotencyKey, long amountCent, String reason) {
        return refundOrder(ownerUserId, requireMerchant(ownerUserId, merchantId), paymentOrderNo,
                idempotencyKey, amountCent, reason);
    }

    private Refund refundOrder(UUID ownerUserId, MerchantRow merchant, String paymentOrderNo,
            String idempotencyKey, long amountCent, String reason) {
        MerchantRepository.MerchantOrderDetailRow order = repository.findOrder(merchant.merchantId(), paymentOrderNo)
                .orElseThrow(() -> problem("MERCHANT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        Refund result = refunds.create(idempotencyKey, order.paymentOrderId(), amountCent, reason);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "ORDER_REFUND_REQUESTED",
                "PAYMENT_ORDER", paymentOrderNo, idempotencyKey, null, null, result.status());
        return result;
    }

    @Transactional
    public MerchantView updateProfile(UUID ownerUserId, long version, String category, String contactName,
            String contactMobile) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return updateProfile(ownerUserId, merchant, version,
                merchant.shortName(), category, merchant.address(), merchant.latitude(), merchant.longitude(),
                merchant.shopImages(), contactName, contactMobile, merchant.contactEmail(), merchant.remark());
    }

    @Transactional
    public MerchantView updateProfile(UUID ownerUserId, UUID merchantId, long version,
            String shortName, String category, String address, BigDecimal latitude, BigDecimal longitude,
            String shopImages, String contactName, String contactMobile, String contactEmail, String remark) {
        return updateProfile(ownerUserId, requireMerchant(ownerUserId, merchantId), version,
                shortName, category, address, latitude, longitude, shopImages, contactName, contactMobile,
                contactEmail, remark);
    }

    private MerchantView updateProfile(UUID ownerUserId, MerchantRow merchant, long version,
            String shortName, String category, String address, BigDecimal latitude, BigDecimal longitude,
            String shopImages, String contactName, String contactMobile, String contactEmail, String remark) {
        requireWritable(merchant);
        // 未传的字段保留原值，避免把运营方已填内容清空。
        String resolvedShortName = shortName == null || shortName.isBlank() ? merchant.shortName() : shortName;
        String resolvedCategory = category == null || category.isBlank() ? merchant.category() : category;
        // 联系电话锁定为登录账号手机号：与商户现有手机号不一致即拒绝，防止手机号归属被改乱。
        String resolvedMobile = contactMobile == null || contactMobile.isBlank()
                ? merchant.contactMobile() : contactMobile.strip();
        if (merchant.contactMobile() != null && !resolvedMobile.equals(merchant.contactMobile())) {
            throw problem("CONTACT_MOBILE_LOCKED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateMerchantFields(merchant.name(), resolvedShortName, resolvedCategory, resolvedMobile);
        if (!repository.updateOwnerProfile(merchant, resolvedShortName, resolvedCategory,
                blankToNull(contactName), resolvedMobile, blankToNull(contactEmail),
                blankToNull(address), latitude, longitude, blankToNull(shopImages), blankToNull(remark), version)) {
            throw problem("MERCHANT_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        MerchantRow updated = repository.findOwned(ownerUserId, merchant.merchantId()).orElseThrow();
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "MERCHANT_PROFILE_UPDATED",
                "MERCHANT", merchant.merchantNo(), null, null,
                merchant.category() + ":" + maskMobile(merchant.contactMobile()),
                updated.category() + ":" + maskMobile(updated.contactMobile()));
        return MerchantView.from(updated);
    }

    public ActiveMerchantView activeByOwner(UUID ownerUserId) {
        MerchantRow active = repository.findAllByOwner(ownerUserId).stream()
                .filter(merchant -> merchant.status() == MerchantStatus.ACTIVE)
                .findFirst()
                .orElse(null);
        return new ActiveMerchantView(active != null,
                active == null ? null : MerchantView.from(active));
    }

    public OpsTodoView opsTodos() {
        return new OpsTodoView(repository.countPendingOnboardings(), repository.countNotificationsAwaitingManualRetry());
    }

    public List<ApplicationView> applications(UUID ownerUserId) {
        return repository.findApplications(requireMerchant(ownerUserId).merchantId()).stream()
                .map(ApplicationView::from).toList();
    }

    public List<ApplicationView> applications(UUID ownerUserId, UUID merchantId) {
        MerchantRow merchant = requireMerchant(ownerUserId, merchantId);
        return repository.findApplications(merchant.merchantId()).stream()
                .map(ApplicationView::from).toList();
    }

    @Transactional
    public void provisionApprovedApplication(
            UUID merchantId, UUID applicationId, String appId, String actorId) {
        MerchantSecretCipher.SecretMaterial secret = secretCipher.newSecret();
        repository.provisionApprovedApplication(
                merchantId, applicationId, secret.ciphertext());
        repository.audit(merchantId, actorId, "ADMIN", "APPLICATION_PROVISIONED",
                "APPLICATION", appId, null, null, null, "DISABLED");
    }

    public ApplicationView application(UUID ownerUserId, String appId) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return ApplicationView.from(ownedApplication(merchant, appId));
    }

    public ApplicationView application(UUID ownerUserId, UUID merchantId, String appId) {
        return ApplicationView.from(ownedApplication(requireMerchant(ownerUserId, merchantId), appId));
    }

    @Transactional
    public ApplicationSecretView createApplication(UUID ownerUserId, ApplicationInput input) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return createApplication(ownerUserId, merchant, input);
    }

    @Transactional
    public ApplicationSecretView createApplication(UUID ownerUserId, UUID merchantId, ApplicationInput input) {
        return createApplication(ownerUserId, requireMerchant(ownerUserId, merchantId), input);
    }

    private ApplicationSecretView createApplication(UUID ownerUserId, MerchantRow merchant, ApplicationInput input) {
        requireWritable(merchant);
        ValidApplicationInput valid = validateApplicationInput(input);
        MerchantSecretCipher.SecretMaterial secret = secretCipher.newSecret();
        MerchantApplicationRow application = repository.createApplication(merchant.merchantId(), valid.name(),
                valid.notifyUrl(), valid.refundNotifyUrl(), valid.ipWhiteList(), valid.permissions(),
                valid.channels(), secret.ciphertext());
        CollectionCodeRow code = repository.findCollectionCode(application.applicationId()).orElseThrow();
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "APPLICATION_CREATED",
                "APPLICATION", application.appId(), null, null, null, "ACTIVE");
        return new ApplicationSecretView(ApplicationView.from(application), secret.plaintext(), CollectionCodeView.from(code));
    }

    @Transactional
    public ApplicationView updateApplication(UUID ownerUserId, String appId, long version, ApplicationInput input) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return updateApplication(ownerUserId, merchant, appId, version, input);
    }

    @Transactional
    public ApplicationView updateApplication(UUID ownerUserId, UUID merchantId, String appId,
            long version, ApplicationInput input) {
        return updateApplication(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version, input);
    }

    private ApplicationView updateApplication(UUID ownerUserId, MerchantRow merchant, String appId,
            long version, ApplicationInput input) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        ValidApplicationInput valid = validateApplicationInput(input);
        if (!repository.updateApplication(application, valid.name(), valid.notifyUrl(), valid.refundNotifyUrl(),
                valid.ipWhiteList(), valid.permissions(), valid.channels(), version)) {
            throw problem("APPLICATION_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        MerchantApplicationRow updated = ownedApplication(merchant, appId);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "APPLICATION_UPDATED",
                "APPLICATION", appId, null, null, null, "UPDATED");
        return ApplicationView.from(updated);
    }

    @Transactional
    public ApplicationView setApplicationStatus(UUID ownerUserId, String appId, long version, boolean enabled) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return setApplicationStatus(ownerUserId, merchant, appId, version, enabled);
    }

    @Transactional
    public ApplicationView setApplicationStatus(UUID ownerUserId, UUID merchantId, String appId,
            long version, boolean enabled) {
        return setApplicationStatus(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version, enabled);
    }

    private ApplicationView setApplicationStatus(UUID ownerUserId, MerchantRow merchant, String appId,
            long version, boolean enabled) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        if (!repository.updateApplicationStatus(application, enabled ? "ACTIVE" : "DISABLED", version)) {
            throw problem("APPLICATION_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        MerchantApplicationRow updated = ownedApplication(merchant, appId);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER",
                enabled ? "APPLICATION_ENABLED" : "APPLICATION_DISABLED", "APPLICATION", appId,
                null, null, application.status(), updated.status());
        return ApplicationView.from(updated);
    }

    @Transactional
    public void deleteApplication(UUID ownerUserId, String appId, long version) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        deleteApplication(ownerUserId, merchant, appId, version);
    }

    @Transactional
    public void deleteApplication(UUID ownerUserId, UUID merchantId, String appId, long version) {
        deleteApplication(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version);
    }

    private void deleteApplication(UUID ownerUserId, MerchantRow merchant, String appId, long version) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        if (application.defaultApplication()) throw problem("DEFAULT_APPLICATION_CANNOT_BE_DELETED", HttpStatus.UNPROCESSABLE_ENTITY);
        if (application.hasTransactions()) throw problem("APPLICATION_HAS_TRANSACTIONS", HttpStatus.CONFLICT);
        if (!repository.deleteApplication(application, version)) throw problem("APPLICATION_VERSION_CONFLICT", HttpStatus.CONFLICT);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "APPLICATION_DELETED",
                "APPLICATION", appId, null, null, "ACTIVE", "DELETED");
    }

    @Transactional
    public ApplicationSecretView resetApplicationSecret(UUID ownerUserId, String appId, long version) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return resetApplicationSecret(ownerUserId, merchant, appId, version);
    }

    @Transactional
    public ApplicationSecretView resetApplicationSecret(UUID ownerUserId, UUID merchantId, String appId, long version) {
        return resetApplicationSecret(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version);
    }

    private ApplicationSecretView resetApplicationSecret(UUID ownerUserId, MerchantRow merchant,
            String appId, long version) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        MerchantSecretCipher.SecretMaterial secret = secretCipher.newSecret();
        if (!repository.rotateApplicationSecret(application, secret.ciphertext(), version)) {
            throw problem("APPLICATION_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        MerchantApplicationRow updated = ownedApplication(merchant, appId);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "APPLICATION_SECRET_RESET",
                "APPLICATION", appId, null, null, "ROTATED", "ROTATED");
        return new ApplicationSecretView(ApplicationView.from(updated), null, null);
    }

    @Transactional
    public ApplicationSecretView viewApplicationSecret(
            UUID ownerUserId, UUID merchantId, String appId) {
        MerchantRow merchant = requireMerchant(ownerUserId, merchantId);
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        String plaintext = repository.claimApplicationSecret(
                        merchant.merchantId(), application.applicationId())
                .orElseThrow(() -> problem("APPLICATION_SECRET_ALREADY_VIEWED", HttpStatus.CONFLICT));
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER",
                "APPLICATION_SECRET_VIEWED", "APPLICATION", appId, null, null, null, "VIEWED");
        return new ApplicationSecretView(ApplicationView.from(application), plaintext, null);
    }

    @Transactional
    public CollectionCodeView collectionCode(UUID ownerUserId, String appId) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        return CollectionCodeView.from(repository.ensureCollectionCode(
                application.applicationId(), "ACTIVE".equals(application.status())));
    }

    @Transactional
    public CollectionCodeView collectionCode(UUID ownerUserId, UUID merchantId, String appId) {
        MerchantRow merchant = requireMerchant(ownerUserId, merchantId);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        return CollectionCodeView.from(repository.ensureCollectionCode(
                application.applicationId(), "ACTIVE".equals(application.status())));
    }

    public CollectionCodeView currentBusinessCollectionCode(UUID ownerUserId) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        MerchantApplicationRow application = repository.findDefaultApplication(merchant.merchantId())
                .orElseThrow(() -> problem("MERCHANT_NOT_INITIALIZED", HttpStatus.CONFLICT));
        return CollectionCodeView.from(repository.findCollectionCode(application.applicationId())
                .orElseThrow(() -> problem("COLLECTION_CODE_NOT_FOUND", HttpStatus.NOT_FOUND)));
    }

    @Transactional
    public CollectionCodeView replaceCurrentBusinessCollectionCode(UUID ownerUserId, long version) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        MerchantApplicationRow application = repository.findDefaultApplication(merchant.merchantId())
                .orElseThrow(() -> problem("MERCHANT_NOT_INITIALIZED", HttpStatus.CONFLICT));
        return replaceCollectionCode(ownerUserId, application.appId(), version);
    }

    @Transactional
    public CollectionCodeView replaceCollectionCode(UUID ownerUserId, String appId, long version) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return replaceCollectionCode(ownerUserId, merchant, appId, version);
    }

    @Transactional
    public CollectionCodeView replaceCollectionCode(UUID ownerUserId, UUID merchantId, String appId, long version) {
        return replaceCollectionCode(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version);
    }

    private CollectionCodeView replaceCollectionCode(UUID ownerUserId, MerchantRow merchant,
            String appId, long version) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        if (!"ACTIVE".equals(application.status())) throw problem("APPLICATION_DISABLED", HttpStatus.CONFLICT);
        CollectionCodeRow code = repository.replaceCollectionCode(application.applicationId(), version)
                .orElseThrow(() -> problem("COLLECTION_CODE_VERSION_CONFLICT", HttpStatus.CONFLICT));
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER", "COLLECTION_CODE_REPLACED",
                "COLLECTION_CODE", code.codeId().toString(), null, null, null, "REPLACED");
        return CollectionCodeView.from(code);
    }

    @Transactional
    public CollectionCodeView setCollectionCodeStatus(UUID ownerUserId, String appId, long version, boolean enabled) {
        MerchantRow merchant = requireMerchant(ownerUserId);
        return setCollectionCodeStatus(ownerUserId, merchant, appId, version, enabled);
    }

    @Transactional
    public CollectionCodeView setCollectionCodeStatus(UUID ownerUserId, UUID merchantId, String appId,
            long version, boolean enabled) {
        return setCollectionCodeStatus(ownerUserId, requireMerchant(ownerUserId, merchantId), appId, version, enabled);
    }

    private CollectionCodeView setCollectionCodeStatus(UUID ownerUserId, MerchantRow merchant,
            String appId, long version, boolean enabled) {
        requireWritable(merchant);
        MerchantApplicationRow application = ownedApplication(merchant, appId);
        if (!"ACTIVE".equals(application.status()) && enabled) throw problem("APPLICATION_DISABLED", HttpStatus.CONFLICT);
        if (!repository.updateCollectionCodeStatus(application.applicationId(), enabled ? "ENABLED" : "DISABLED", version)) {
            throw problem("COLLECTION_CODE_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        CollectionCodeView updated = collectionCode(ownerUserId, merchant.merchantId(), appId);
        repository.audit(merchant.merchantId(), ownerUserId.toString(), "MERCHANT_OWNER",
                enabled ? "COLLECTION_CODE_ENABLED" : "COLLECTION_CODE_DISABLED", "COLLECTION_CODE",
                updated.codeId(), null, null, enabled ? "DISABLED" : "ENABLED", updated.status());
        return updated;
    }

    @Transactional
    public ResolutionView resolve(String token) {
        if (token == null || token.isBlank() || token.length() > 512) throw problem("INVALID_COLLECTION_CODE", HttpStatus.BAD_REQUEST);
        MerchantRepository.ResolutionSourceRow source = repository.findResolutionSource(MerchantRepository.hash(token))
                .orElseThrow(() -> problem("COLLECTION_CODE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"ACTIVE".equals(source.merchantStatus()) || source.receiveLocked()
                || !"ACTIVE".equals(source.applicationStatus()) || !"ENABLED".equals(source.codeStatus())) {
            throw problem("MERCHANT_COLLECTION_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        List<String> allowedChannels = Arrays.stream(source.availableChannels().split(","))
                .map(String::strip).filter(CHANNELS::contains).toList();
        Instant expiresAt = Instant.now().plusSeconds(600);
        MerchantRepository.ResolutionRow resolution = repository.createResolution(
                source.merchantId(), source.applicationId(), expiresAt);
        return new ResolutionView("MERCHANT_COLLECTION", resolution.resolutionId().toString(), source.merchantNo(), source.merchantName(),
                source.appId(), allowedChannels, expiresAt);
    }

    @Transactional
    public InternalResolutionView consumeResolution(UUID resolutionId, String merchantId, String appId) {
        MerchantRepository.ResolutionRow resolution = repository.consumeResolution(resolutionId, merchantId, appId)
                .orElseThrow(() -> problem("SCAN_RESOLUTION_UNAVAILABLE", HttpStatus.CONFLICT));
        return new InternalResolutionView(resolution.resolutionId().toString(), merchantId, appId,
                resolution.expiresAt(), "CONSUMED");
    }

    private MerchantRow requireMerchant(UUID ownerUserId) {
        return repository.findByOwner(ownerUserId).orElseThrow(() -> problem("MERCHANT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private MerchantRow requireMerchant(UUID ownerUserId, UUID merchantId) {
        if (merchantId == null) {
            throw problem("MERCHANT_ID_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        return repository.findOwned(ownerUserId, merchantId)
                .orElseThrow(() -> problem("MERCHANT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private MerchantApplicationRow ownedApplication(MerchantRow merchant, String appId) {
        if (appId == null || appId.isBlank()) throw problem("INVALID_APP_ID", HttpStatus.BAD_REQUEST);
        return repository.findOwnedApplication(merchant.merchantId(), appId)
                .orElseThrow(() -> problem("MERCHANT_APPLICATION_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private static void requireWritable(MerchantRow merchant) {
        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw problem("MERCHANT_WRITE_FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private void validateMerchantFields(String name, String shortName, String category, String mobile) {
        if (name == null || name.strip().length() < 2 || name.strip().length() > 40) throw problem("INVALID_MERCHANT_NAME", HttpStatus.UNPROCESSABLE_ENTITY);
        contentSafety.checkMerchantName(name);
        if (shortName == null || shortName.strip().length() < 2 || shortName.strip().length() > 32) throw problem("INVALID_MERCHANT_SHORT_NAME", HttpStatus.UNPROCESSABLE_ENTITY);
        if (category == null || !CATEGORIES.contains(category)) throw problem("INVALID_MERCHANT_CATEGORY", HttpStatus.UNPROCESSABLE_ENTITY);
        if (mobile != null && !mobile.isBlank() && !mobile.matches("^1[3-9]\\d{9}$")) throw problem("INVALID_CONTACT_MOBILE", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private ValidApplicationInput validateApplicationInput(ApplicationInput input) {
        if (input == null || input.name() == null || input.name().strip().length() < 2 || input.name().strip().length() > 40) {
            throw problem("INVALID_APPLICATION_NAME", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        contentSafety.checkApplicationName(input.name());
        if (input.notifyUrl() == null || input.notifyUrl().isBlank()) {
            throw problem("NOTIFY_URL_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String notifyUrl = validateHttpsUrl(input.notifyUrl(), "INVALID_NOTIFY_URL");
        String refundNotifyUrl = validateHttpsUrl(input.refundNotifyUrl(), "INVALID_REFUND_NOTIFY_URL");
        String permissions = normaliseSet(input.permissions(), APPLICATION_PERMISSIONS, "INVALID_APPLICATION_PERMISSION", true);
        List<String> requestedChannels = input.availableChannels() == null || input.availableChannels().isEmpty()
                ? List.copyOf(CHANNELS) : input.availableChannels();
        String channels = normaliseSet(requestedChannels, CHANNELS, "INVALID_APPLICATION_CHANNEL", true);
        String ipWhiteList = normaliseIpWhiteList(input.ipWhiteList());
        return new ValidApplicationInput(input.name().strip(), notifyUrl, refundNotifyUrl, ipWhiteList, permissions, channels);
    }

    private static String validateHttpsUrl(String value, String code) {
        if (value == null || value.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || value.length() > 512) throw new IllegalArgumentException();
            return uri.toString();
        } catch (RuntimeException exception) {
            throw problem(code, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static String normaliseSet(List<String> values, Set<String> allowed, String code, boolean required) {
        Set<String> result = values == null ? Set.of() : values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::strip).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if ((required && result.isEmpty()) || !allowed.containsAll(result)) throw problem(code, HttpStatus.UNPROCESSABLE_ENTITY);
        return String.join(",", result);
    }

    private static String normaliseIpWhiteList(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        List<String> result = values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
        if (result.size() != values.size() || result.stream().anyMatch(value -> !value.matches("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?:/(?:[0-9]|[12][0-9]|3[0-2]))?$"))) {
            throw problem("INVALID_IP_WHITE_LIST", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String joined = String.join(",", result);
        if (joined.length() > 1000) throw problem("INVALID_IP_WHITE_LIST", HttpStatus.UNPROCESSABLE_ENTITY);
        return joined;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String maskMobile(String value) {
        return value != null && value.matches("^1[3-9]\\d{9}$") ? value.substring(0, 3) + "****" + value.substring(7) : null;
    }
    private static PaymentProblemException problem(String code, HttpStatus status) { return new PaymentProblemException(code, status); }

    public record MerchantView(String merchantId, String merchantNo, String name, String shortName, String category,
                               String contactName, String contactMobile, String contactEmail, String address,
                               BigDecimal latitude, BigDecimal longitude, String shopImages,
                               String status, boolean receiveLocked, String remark,
                               boolean initialized, boolean profileConfirmationRequired, long version) {
        static MerchantView from(MerchantRow row) {
            return new MerchantView(row.merchantId().toString(), row.merchantNo(), row.name(), row.shortName(),
                    row.category(), row.contactName(), row.contactMobile(), row.contactEmail(), row.address(),
                    row.latitude(), row.longitude(), row.shopImages(),
                    row.status().name(), row.receiveLocked(), row.remark(), row.defaultApplicationId() != null,
                    "OPS".equals(row.source()) && row.profileConfirmedAt() == null, row.version());
        }
    }
    public record ApplicationView(String applicationId, String appId, String appName, String status,
                                  String notifyUrl, String refundNotifyUrl, List<String> ipWhiteList,
                                  List<String> permissions, List<String> availableChannels, boolean defaultApplication,
                                  long version) {
        static ApplicationView from(MerchantApplicationRow row) {
            return new ApplicationView(row.applicationId().toString(), row.appId(), row.appName(), row.status(),
                    row.notifyUrl(), row.refundNotifyUrl(), split(row.ipWhiteList()), split(row.apiPermissions()),
                    split(row.availableChannels()), row.defaultApplication(), row.version());
        }
        private static List<String> split(String values) { return values == null || values.isBlank() ? List.of() : Arrays.stream(values.split(",")).map(String::strip).toList(); }
    }
    public record CollectionCodeView(String codeId, String status, long keyVersion, long version,
                                     String token, String qrContent) {
        static CollectionCodeView from(CollectionCodeRow row) {
            return new CollectionCodeView(row.codeId().toString(), row.status(), row.keyVersion(),
                    row.version(), row.token(),
                    "minipay://collect/merchant?token=" + row.token());
        }
    }
    public record InitializationView(MerchantView merchant, ApplicationView defaultApplication,
                                     CollectionCodeView collectionCode, String qrContent,
                                     String appSecret) { }
    public record DailyMetricView(LocalDate statDate, long paymentAmountCent, long paymentCount, long refundAmountCent, long refundCount) { }
    public record DashboardView(int days, long paymentAmountCent, long paymentCount, long refundAmountCent, long refundCount,
                                long todayPaymentAmountCent, long todayPaymentCount, long todayRefundAmountCent, long todayRefundCount,
                                long cumulativePaymentAmountCent, long cumulativePaymentCount, long cumulativeRefundAmountCent, long cumulativeRefundCount,
                                Instant dataAsOf, List<DailyMetricView> items) { }
    public record MerchantOrderView(String paymentOrderNo, String merchantOrderNo, String appId, long amountCent,
                                    String currency, String subject, String channel, String allowedChannels,
                                    String status, Instant expiresAt, Instant createdAt, Instant updatedAt,
                                    String refundNo, Long refundAmountCent, String refundStatus, String refundReason) { }
    public record MerchantOrderPage(List<MerchantOrderView> items, int page, int size, long total) { }
    public record MerchantOrderDetailView(String paymentOrderNo, String merchantOrderNo, String appId, long amountCent,
                                          String currency, String subject, String channel, String allowedChannels,
                                          String status, Instant expiresAt, Instant createdAt, Instant updatedAt,
                                          String refundNo, Long refundAmountCent, String refundStatus, String refundReason) {
        static MerchantOrderDetailView from(MerchantRepository.MerchantOrderDetailRow row) {
            return new MerchantOrderDetailView(row.paymentOrderNo(), row.merchantOrderNo(), row.appId(), row.amountCent(),
                    row.currency(), row.subject(), row.channel(), row.allowedChannels(), row.status(), row.expiresAt(),
                    row.createdAt(), row.updatedAt(), row.refundNo(), row.refundAmountCent(), row.refundStatus(), row.refundReason());
        }
    }
    public record ChannelDistributionView(String appId, String channel, long orderCount, long amountCent) { }
    public record ActiveMerchantView(boolean active, MerchantView merchant) { }
    public record OpsTodoView(long pendingOnboardings, long notificationsAwaitingManualRetry) { }
    public record ResolutionView(String type, String resolutionId, String merchantId, String merchantName, String appId, List<String> allowedChannels, Instant expiresAt) { }
    public record InternalResolutionView(String resolutionId, String merchantId, String appId, Instant expiresAt, String status) { }
    public record ApplicationInput(String name, String notifyUrl, String refundNotifyUrl, List<String> ipWhiteList,
                                   List<String> permissions, List<String> availableChannels) { }
    private record ValidApplicationInput(String name, String notifyUrl, String refundNotifyUrl, String ipWhiteList,
                                         String permissions, String channels) { }
    public record ApplicationSecretView(ApplicationView application, String appSecret, CollectionCodeView collectionCode) { }
}
