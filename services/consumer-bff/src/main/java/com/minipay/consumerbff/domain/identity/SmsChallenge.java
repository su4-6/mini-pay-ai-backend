package com.minipay.consumerbff.domain.identity;

import java.time.Instant;

/** One SMS challenge as issued by Identity. The upstream demo code is optional and demo-only. */
public record SmsChallenge(String challengeId, Instant expiresAt, String demoCode) {
}
