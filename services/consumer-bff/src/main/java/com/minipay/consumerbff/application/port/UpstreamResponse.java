package com.minipay.consumerbff.application.port;

import java.time.Instant;

/**
 * Raw upstream HTTP outcome. The BFF relays the resource document verbatim so the H5 client keeps
 * seeing the authoritative upstream field names, while the status code and content type are
 * preserved for error transparency.
 */
public record UpstreamResponse(int status, String contentType, String body, Instant receivedAt) {

    public boolean successful() {
        return status >= 200 && status < 300;
    }
}
