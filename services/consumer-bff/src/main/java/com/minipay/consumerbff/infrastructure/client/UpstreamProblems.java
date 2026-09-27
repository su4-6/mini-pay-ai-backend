package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.minipay.consumerbff.application.error.UpstreamProblemException;
import org.springframework.http.HttpStatus;

/** Converts an upstream rejection into a problem without echoing the upstream document. */
final class UpstreamProblems {

    private UpstreamProblems() {
    }

    static UpstreamProblemException from(int status, JsonNode document, String fallbackCode) {
        String code = Values.text(document, "code");
        if (code == null) {
            code = Values.text(document, "error");
        }
        String detail = Values.text(document, "detail");
        if (detail == null) {
            detail = Values.text(document, "title");
        }
        return new UpstreamProblemException(
                statusOf(status), code == null ? fallbackCode : code, detail);
    }

    static UpstreamProblemException of(int status, String code) {
        return new UpstreamProblemException(statusOf(status), code, null);
    }

    static UpstreamProblemException gateway(String code, String detail) {
        return new UpstreamProblemException(HttpStatus.BAD_GATEWAY, code, detail);
    }

    private static HttpStatus statusOf(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
    }
}
