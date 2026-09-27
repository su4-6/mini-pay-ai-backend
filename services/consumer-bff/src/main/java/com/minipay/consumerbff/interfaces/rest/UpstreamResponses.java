package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.port.UpstreamResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared response shaping for the read-only proxy endpoints. */
final class UpstreamResponses {

    private UpstreamResponses() {
    }

    static ResponseEntity<String> toEntity(UpstreamResponse response) {
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(response.contentType());
        } catch (RuntimeException exception) {
            contentType = MediaType.APPLICATION_JSON;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(contentType)
                // Authoritative wallet and payment reads are never cached by the browser or a proxy.
                .cacheControl(CacheControl.noStore());
        return response.body() == null || response.body().isBlank()
                ? builder.build()
                : builder.body(response.body());
    }

    static ResponseEntity<Map<String, Object>> prepared(
            String transferIntentId, String payeeMasked, long amountFen, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferIntentId", transferIntentId);
        body.put("payeeMasked", payeeMasked);
        body.put("amountFen", amountFen);
        body.put("expiresAt", expiresAt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static ResponseEntity<Map<String, Object>> confirmed(
            String transferNo, String status, String failureCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", transferNo);
        body.put("status", status);
        if (failureCode != null) {
            body.put("failureCode", failureCode);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static ResponseEntity<Map<String, Object>> preparedPayment(
            String paymentOrderId, String paymentOrderNo, long amountFen, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentOrderId", paymentOrderId);
        body.put("paymentOrderNo", paymentOrderNo);
        body.put("amountFen", amountFen);
        body.put("expiresAt", expiresAt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static ResponseEntity<Map<String, Object>> confirmedPayment(
            String paymentOrderNo, String status, String failureCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentOrderNo", paymentOrderNo);
        body.put("status", status);
        if (failureCode != null) {
            body.put("failureCode", failureCode);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
