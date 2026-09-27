package com.minipay.consumerbff.application.error;

import org.springframework.http.HttpStatus;

/**
 * Upstream rejected a call with a stable business code. Carries no credentials and never carries
 * the request body, so relaying it to the browser cannot leak secrets.
 */
public class UpstreamProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String detail;

    public UpstreamProblemException(HttpStatus status, String code, String detail) {
        super(code);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public static UpstreamProblemException of(HttpStatus status, String code) {
        return new UpstreamProblemException(status, code, null);
    }
}
