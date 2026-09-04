package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletApplicationService;
import com.minipay.wallet.application.service.WalletProblemException;
import com.minipay.wallet.domain.model.BillPage;
import com.minipay.wallet.domain.model.BillQuery;
import com.minipay.wallet.domain.model.WalletBill;
import com.minipay.wallet.domain.model.WalletBillDetail;
import com.minipay.wallet.domain.model.BillTag;
import com.minipay.wallet.domain.model.BillTagPage;
import com.minipay.wallet.domain.model.CollectionRecordPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.minipay.wallet.domain.model.WalletSummary;
import com.minipay.wallet.application.service.WalletApplicationService.RecentTransferCounterpartyPage;
import com.minipay.wallet.application.service.WalletApplicationService.TransferRecordPage;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets/me")
public class WalletController {
    private final WalletApplicationService wallets;

    public WalletController(WalletApplicationService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    public WalletSummary getWallet(JwtAuthenticationToken authentication) {
        return wallets.getWallet(currentUser(authentication));
    }

    @GetMapping("/bills")
    public BillPage listBills(
            JwtAuthenticationToken authentication,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return wallets.listBills(
                currentUser(authentication),
                new BillQuery(from, to, direction, businessType, source, status, page, size));
    }

    @GetMapping("/bills/{billId}")
    public WalletBillDetail getBill(
            JwtAuthenticationToken authentication,
            @PathVariable UUID billId) {
        return wallets.getBill(currentUser(authentication), billId);
    }

    @GetMapping("/collection-records")
    public CollectionRecordPage collectionRecords(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "TODAY") String period,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return wallets.collectionRecords(currentUser(authentication), type, period, page, size);
    }

    @GetMapping("/recent-transfer-counterparties")
    public RecentTransferCounterpartyPage recentTransferCounterparties(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return wallets.recentTransferCounterparties(currentUser(authentication), page, size);
    }

    @GetMapping("/transfer-records")
    public TransferRecordPage transferRecords(
            JwtAuthenticationToken authentication,
            @RequestParam UUID counterpartyUserId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        java.time.Instant from = null;
        java.time.Instant to = null;
        if (month != null && !month.isBlank()) {
            try {
                var selected = YearMonth.parse(month);
                var zone = ZoneId.of("Asia/Shanghai");
                from = selected.atDay(1).atStartOfDay(zone).toInstant();
                to = selected.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            } catch (java.time.format.DateTimeParseException error) {
                throw new com.minipay.wallet.application.service.WalletProblemException(
                        "INVALID_TRANSFER_MONTH", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        return wallets.transferRecords(currentUser(authentication), counterpartyUserId,
                direction, status, from, to, page, size);
    }

    @PutMapping("/bills/{billId}/management")
    public WalletBillDetail updateBillManagement(
            JwtAuthenticationToken authentication,
            @PathVariable UUID billId,
            @Valid @RequestBody UpdateBillManagementRequest request) {
        return wallets.updateBillManagement(currentUser(authentication), billId,
                request.categoryCode(), request.tagIds(), request.userNote(),
                request.includedInStatistics());
    }

    @GetMapping("/bill-tags")
    public BillTagPage listTags(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return wallets.listTags(currentUser(authentication), page, size);
    }

    @PostMapping("/bill-tags")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public BillTag createTag(
            JwtAuthenticationToken authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTagRequest request) {
        return wallets.createTag(currentUser(authentication), idempotencyKey, request.name());
    }

    public record UpdateBillManagementRequest(
            @NotBlank String categoryCode,
            @Size(max = 5) List<UUID> tagIds,
            @Size(max = 200) String userNote,
            boolean includedInStatistics) {
    }

    public record CreateTagRequest(@NotBlank @Size(max = 48) String name) {
    }

    private UUID currentUser(JwtAuthenticationToken authentication) {
        List<String> roles = authentication.getToken().getClaimAsStringList("roles");
        boolean merchantPortal = authentication.getToken().getAudience().contains("merchant-api")
                || (roles != null && roles.contains("merchant_owner"));
        if (!merchantPortal) {
            Boolean completed = authentication.getToken().getClaim("onboarding_completed");
            if (!Boolean.TRUE.equals(completed)) {
                throw new WalletProblemException("ONBOARDING_REQUIRED", HttpStatus.CONFLICT);
            }
            if (!Boolean.TRUE.equals(authentication.getToken().getClaim("real_name_verified"))) {
                String status = authentication.getToken().getClaimAsString("real_name_status");
                throw new WalletProblemException(
                        "PROCESSING".equals(status)
                                ? "REAL_NAME_VERIFICATION_PROCESSING"
                                : "REAL_NAME_VERIFICATION_REQUIRED",
                        HttpStatus.FORBIDDEN);
            }
        }
        String userId = authentication.getToken().getClaimAsString("user_id");
        if (userId == null) {
            throw new WalletProblemException("INVALID_SUBJECT", HttpStatus.FORBIDDEN);
        }
        return UUID.fromString(userId);
    }
}
