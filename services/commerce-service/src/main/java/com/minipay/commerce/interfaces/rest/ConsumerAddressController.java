package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.CommerceAddressService;
import com.minipay.commerce.domain.model.DeliveryAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce/addresses")
public class ConsumerAddressController {
    private final CommerceAddressService addresses;

    public ConsumerAddressController(CommerceAddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    public List<DeliveryAddress> list(@AuthenticationPrincipal Jwt jwt) {
        return addresses.list(userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryAddress create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAddressRequest request) {
        return addresses.create(userId(jwt), request.label(), request.recipient(),
                request.mobile(), request.address(), request.zoneCode(), request.defaultAddress());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    public record CreateAddressRequest(
            @NotBlank @Size(max = 32) String label,
            @NotBlank @Size(max = 64) String recipient,
            @NotBlank @Size(max = 20) String mobile,
            @NotBlank @Size(max = 512) String address,
            @NotBlank @Size(max = 64) String zoneCode,
            boolean defaultAddress) {
    }
}
