package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PersonalCollectionCodeService;
import com.minipay.payment.domain.model.PersonalCollectionCode;
import com.minipay.payment.domain.model.ScanResolution;
import com.minipay.payment.application.service.MerchantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CollectionCodeController {
    private final PersonalCollectionCodeService codes;
    private final MerchantService merchants;

    public CollectionCodeController(PersonalCollectionCodeService codes, MerchantService merchants) {
        this.codes = codes;
        this.merchants = merchants;
    }

    @GetMapping("/personal-collection-codes/current")
    public PersonalCollectionCode current(@AuthenticationPrincipal Jwt jwt) {
        return codes.current(ConsumerClaims.requireReadyUser(jwt, false));
    }

    @PostMapping("/scan-resolutions")
    public Object resolve(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ScanResolutionRequest request) {
        ConsumerClaims.requireReadyUser(jwt, false);
        if (request.merchantToken() != null && !request.merchantToken().isBlank()) {
            return merchants.resolve(request.merchantToken().strip());
        }
        String merchantToken = merchantToken(request.deepLink());
        if (merchantToken != null) {
            return merchants.resolve(merchantToken);
        }
        return codes.resolve(
                ConsumerClaims.requireReadyUser(jwt, false), request.deepLink());
    }

    private static String merchantToken(String deepLink) {
        if (deepLink == null || deepLink.isBlank()) return null;
        try {
            URI uri = URI.create(deepLink);
            if (!"minipay".equalsIgnoreCase(uri.getScheme())
                    || !"collect".equalsIgnoreCase(uri.getHost())
                    || !"/merchant".equals(uri.getPath())) {
                return null;
            }
            if (uri.getRawQuery() == null) return null;
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "token".equals(parts[0])) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record ScanResolutionRequest(
            @Size(max = 2048) String deepLink,
            @Size(max = 512) String merchantToken) {
        @AssertTrue(message = "deepLink or merchantToken is required")
        public boolean hasToken() {
            return (deepLink != null && !deepLink.isBlank()) || (merchantToken != null && !merchantToken.isBlank());
        }
    }
}
