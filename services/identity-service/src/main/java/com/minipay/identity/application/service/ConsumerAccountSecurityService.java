package com.minipay.identity.application.service;

import com.minipay.identity.application.port.EmailSender;
import com.minipay.identity.application.service.ConsumerSmsChallengeService.ConsumerSmsChallenge;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository.AccountSecurityView;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository.OperationRow;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountSecurityRepository.VerificationRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ConsumerAccountSecurityService {
    private static final String CHALLENGE_PREFIX = "minipay:account-security:challenge:";
    private static final String IDEMPOTENCY_PREFIX = "minipay:account-security:idempotency:";
    private static final String VERIFIED_PREFIX = "minipay:account-security:verified:";
    private static final String EMAIL_RESEND_PREFIX = "minipay:account-security:email:resend:";
    private static final String EMAIL_LOCK_PREFIX = "minipay:account-security:email:lock:";
    private static final int MAX_ATTEMPTS = 5;
    private static final DefaultRedisScript<String> VERIFY_EMAIL_SCRIPT = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            if not userId then return 'EXPIRED' end
            if userId ~= ARGV[1] then return 'INVALID' end
            local digest = redis.call('HGET', KEYS[1], 'codeDigest')
            if digest == ARGV[2] then
              local target = redis.call('HGET', KEYS[1], 'target')
              redis.call('DEL', KEYS[1])
              return 'OK:' .. target
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[3]) then
              redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[4]))
              redis.call('DEL', KEYS[1])
              return 'LOCKED'
            end
            return 'INVALID'
            """, String.class);

    private final ConsumerAccountSecurityRepository accounts;
    private final ConsumerSmsChallengeService sms;
    private final PhoneNumberService phones;
    private final EmailAddressService emails;
    private final EmailSender emailSender;
    private final StringRedisTemplate redis;
    private final PaymentPasswordChangeTokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final PhoneDisclosureCipher phoneCipher;
    private final byte[] pepper;
    private final Duration emailTtl;
    private final Duration resendAfter;
    private final Duration lockDuration;
    private final Duration paymentVerificationTtl;
    private final SecureRandom random = new SecureRandom();

    public ConsumerAccountSecurityService(
            ConsumerAccountSecurityRepository accounts,
            ConsumerSmsChallengeService sms,
            PhoneNumberService phones,
            EmailAddressService emails,
            EmailSender emailSender,
            StringRedisTemplate redis,
            PaymentPasswordChangeTokenService tokens,
            PasswordEncoder passwordEncoder,
            PhoneDisclosureCipher phoneCipher,
            @Value("${minipay.identity.captcha-pepper}") String pepper,
            @Value("${minipay.identity.email.code-ttl:5m}") Duration emailTtl,
            @Value("${minipay.identity.sms.resend-after:60s}") Duration resendAfter,
            @Value("${minipay.identity.sms.consumer-lock-duration:10m}") Duration lockDuration,
            @Value("${minipay.identity.payment-password-change-verification-ttl:5m}")
            Duration paymentVerificationTtl) {
        this.accounts = accounts;
        this.sms = sms;
        this.phones = phones;
        this.emails = emails;
        this.emailSender = emailSender;
        this.redis = redis;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.phoneCipher = phoneCipher;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.emailTtl = emailTtl;
        this.resendAfter = resendAfter;
        this.lockDuration = lockDuration;
        this.paymentVerificationTtl = paymentVerificationTtl;
    }

    public AccountSecurityView get(UUID userId) {
        return accounts.get(userId);
    }

    public VerificationChallenge requestPhoneChange(
            UUID userId,
            String mobile,
            String clientAddress,
            String idempotencyKey) {
        String normalized = phones.normalize(mobile);
        byte[] requestHash = requestHash("PHONE_CHANGE_CHALLENGE", normalized);
        return requestSmsChallenge(
                userId, "PHONE_CHANGE", normalized, "", clientAddress, idempotencyKey, requestHash);
    }

    public void confirmPhoneChange(
            UUID userId, String challengeId, String code, String idempotencyKey) {
        byte[] requestHash = requestHash("PHONE_CHANGE", challengeId);
        if (completed(userId, "PHONE_CHANGE", idempotencyKey, requestHash)) return;
        String mobile = consumeSmsChallenge(userId, "PHONE_CHANGE", challengeId, code, "");
        PhoneDisclosureCipher.EncryptedPhone encrypted = phoneCipher.encrypt(userId, mobile);
        try {
            accounts.changePhoneAndRevokeSessions(
                    userId, phones.hash(mobile), phones.mask(mobile), encrypted,
                    idempotencyKey, requestHash);
            redis.delete(VERIFIED_PREFIX + challengeId);
        } catch (DuplicateKeyException exception) {
            if (!completed(userId, "PHONE_CHANGE", idempotencyKey, requestHash)) throw exception;
        }
    }

    public VerificationChallenge requestPhoneDisclosure(
            UUID userId,
            String mobile,
            String clientAddress,
            String idempotencyKey) {
        String normalized = phones.normalize(mobile);
        if (!accounts.mobileMatches(userId, phones.hash(normalized))) {
            throw new AccountSecurityRejectedException("CURRENT_MOBILE_MISMATCH");
        }
        byte[] requestHash = requestHash("PHONE_DISCLOSURE_CHALLENGE", normalized);
        return requestSmsChallenge(userId, "PHONE_DISCLOSURE", normalized, "",
                clientAddress, idempotencyKey, requestHash);
    }

    public void confirmPhoneDisclosure(
            UUID userId, String challengeId, String code, String idempotencyKey) {
        byte[] requestHash = requestHash("PHONE_DISCLOSURE", challengeId);
        if (completed(userId, "PHONE_DISCLOSURE", idempotencyKey, requestHash)) return;
        String mobile = consumeSmsChallenge(userId, "PHONE_DISCLOSURE", challengeId, code, "");
        PhoneDisclosureCipher.EncryptedPhone encrypted = phoneCipher.encrypt(userId, mobile);
        accounts.storePhoneDisclosure(userId, encrypted, idempotencyKey, requestHash);
        redis.delete(VERIFIED_PREFIX + challengeId);
    }

    public VerificationChallenge requestEmail(
            UUID userId, String email, String idempotencyKey) {
        String normalized = emails.normalize(email);
        byte[] requestHash = requestHash("EMAIL_CHALLENGE", normalized);
        VerificationChallenge replay = challengeReplay(userId, "EMAIL", idempotencyKey, requestHash);
        if (replay != null) return replay;

        String lockKey = EMAIL_LOCK_PREFIX + userId;
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
            throw new AccountSecurityRejectedException("EMAIL_CODE_LOCKED", ttl(lockKey));
        }
        String resendKey = EMAIL_RESEND_PREFIX + userId;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(resendKey, "1", resendAfter))) {
            throw new AccountSecurityRejectedException("EMAIL_RESEND_TOO_SOON", ttl(resendKey));
        }

        String challengeId = UUID.randomUUID().toString();
        String challengeKey = CHALLENGE_PREFIX + challengeId;
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plus(emailTtl);
        redis.opsForHash().putAll(challengeKey, Map.of(
                "userId", userId.toString(),
                "purpose", "EMAIL",
                "target", normalized,
                "maskedTarget", emails.mask(normalized),
                "codeDigest", digestHex(code),
                "attempts", "0",
                "expiresAt", expiresAt.toString()));
        redis.expire(challengeKey, emailTtl);
        try {
            emailSender.sendVerificationCode(normalized, code);
            VerificationChallenge result = new VerificationChallenge(
                    challengeId, emails.mask(normalized), expiresAt, resendAfter.toSeconds());
            storeChallengeIdempotency(userId, "EMAIL", idempotencyKey, requestHash, result);
            return result;
        } catch (RuntimeException exception) {
            redis.delete(challengeKey);
            redis.delete(resendKey);
            throw exception;
        }
    }

    public void confirmEmail(
            UUID userId, String challengeId, String code, String idempotencyKey) {
        byte[] requestHash = requestHash("EMAIL_CONFIRM", challengeId);
        if (completed(userId, "EMAIL_CONFIRM", idempotencyKey, requestHash)) return;
        String email = consumeEmailChallenge(userId, challengeId, code);
        try {
            accounts.bindEmail(
                    userId, emails.hmac(email), emails.mask(email), idempotencyKey, requestHash);
            redis.delete(VERIFIED_PREFIX + challengeId);
        } catch (DuplicateKeyException exception) {
            if (!completed(userId, "EMAIL_CONFIRM", idempotencyKey, requestHash)) throw exception;
        }
    }

    public void removeEmail(UUID userId, String idempotencyKey) {
        byte[] requestHash = requestHash("EMAIL_DELETE", userId.toString());
        if (completed(userId, "EMAIL_DELETE", idempotencyKey, requestHash)) return;
        try {
            accounts.removeEmail(userId, idempotencyKey, requestHash);
        } catch (DuplicateKeyException exception) {
            if (!completed(userId, "EMAIL_DELETE", idempotencyKey, requestHash)) throw exception;
        }
    }

    public VerificationChallenge requestPaymentPasswordChallenge(
            UUID userId,
            String mobile,
            String deviceId,
            String clientAddress,
            String idempotencyKey) {
        String normalized = phones.normalize(mobile);
        if (!accounts.mobileMatches(userId, phones.hash(normalized))) {
            throw new AccountSecurityRejectedException("CURRENT_MOBILE_MISMATCH");
        }
        requireDevice(deviceId);
        byte[] requestHash = requestHash(
                "PAYMENT_PASSWORD_CHALLENGE", normalized + "|" + deviceId);
        return requestSmsChallenge(userId, "PAYMENT_PASSWORD_CHANGE", normalized,
                deviceId, clientAddress, idempotencyKey, requestHash);
    }

    public PaymentPasswordVerification verifyPaymentPasswordChallenge(
            UUID userId,
            String challengeId,
            String code,
            String deviceId,
            String idempotencyKey) {
        requireDevice(deviceId);
        byte[] requestHash = requestHash(
                "PAYMENT_PASSWORD_VERIFY", challengeId + "|" + deviceId);
        Optional<VerificationRow> replay = accounts.findVerificationByIssueKey(userId, idempotencyKey);
        if (replay.isPresent()) {
            VerificationRow row = replay.get();
            ensureSameRequest(row.issueRequestHash(), requestHash);
            return verificationResponse(row);
        }

        consumeSmsChallenge(userId, "PAYMENT_PASSWORD_CHANGE", challengeId, code, deviceId);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(paymentVerificationTtl);
        UUID verificationId = UuidV7.generate();
        try {
            accounts.createVerification(verificationId, userId, challengeId, idempotencyKey,
                    requestHash, deviceId, issuedAt, expiresAt);
        } catch (DuplicateKeyException exception) {
            VerificationRow existing = accounts.findVerificationByIssueKey(userId, idempotencyKey)
                    .orElseThrow(() -> exception);
            ensureSameRequest(existing.issueRequestHash(), requestHash);
            return verificationResponse(existing);
        }
        redis.delete(VERIFIED_PREFIX + challengeId);
        return new PaymentPasswordVerification(
                tokens.issue(verificationId, userId, deviceId, issuedAt, expiresAt), expiresAt);
    }

    public void changePaymentPassword(
            UUID userId,
            String verificationToken,
            String newPassword,
            String deviceId,
            String idempotencyKey) {
        requireDevice(deviceId);
        if (newPassword == null || !newPassword.matches("\\d{6}")) {
            throw new AccountSecurityRejectedException("PAYMENT_PASSWORD_INVALID");
        }
        PaymentPasswordChangeTokenService.Claims claims = tokens.verify(verificationToken);
        if (!claims.userId().equals(userId)) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_INVALID");
        }
        if (!claims.deviceId().equals(deviceId)) {
            throw new AccountSecurityRejectedException("DEVICE_MISMATCH");
        }
        byte[] requestHash = requestHash("PAYMENT_PASSWORD_CHANGE",
                claims.verificationId() + "|" + deviceId + "|" + newPassword);
        if (completed(userId, "PAYMENT_PASSWORD_CHANGE", idempotencyKey, requestHash)) return;
        String encoded = passwordEncoder.encode(newPassword);
        try {
            accounts.changePaymentPassword(claims.verificationId(), userId, deviceId,
                    encoded, idempotencyKey, requestHash, Instant.now());
        } catch (DuplicateKeyException exception) {
            if (!completed(userId, "PAYMENT_PASSWORD_CHANGE", idempotencyKey, requestHash)) {
                throw exception;
            }
        }
    }

    private VerificationChallenge requestSmsChallenge(
            UUID userId,
            String purpose,
            String mobile,
            String deviceId,
            String clientAddress,
            String idempotencyKey,
            byte[] requestHash) {
        VerificationChallenge replay = challengeReplay(userId, purpose, idempotencyKey, requestHash);
        if (replay != null) return replay;
        ConsumerSmsChallenge challenge = sms.create(mobile, clientAddress);
        VerificationChallenge result = new VerificationChallenge(
                challenge.challengeId(), challenge.maskedMobile(),
                challenge.expiresAt(), challenge.resendAfterSeconds());
        String key = CHALLENGE_PREFIX + challenge.challengeId();
        redis.opsForHash().putAll(key, Map.of(
                "userId", userId.toString(),
                "purpose", purpose,
                "deviceId", deviceId,
                "maskedTarget", challenge.maskedMobile(),
                "expiresAt", challenge.expiresAt().toString()));
        redis.expireAt(key, challenge.expiresAt());
        storeChallengeIdempotency(userId, purpose, idempotencyKey, requestHash, result);
        return result;
    }

    private String consumeSmsChallenge(
            UUID userId, String purpose, String challengeId, String code, String deviceId) {
        String verified = verifiedTarget(userId, purpose, challengeId, deviceId);
        if (verified != null) return verified;
        Map<Object, Object> binding = redis.opsForHash().entries(CHALLENGE_PREFIX + challengeId);
        if (!userId.toString().equals(binding.get("userId"))
                || !purpose.equals(binding.get("purpose"))) {
            throw new AccountSecurityRejectedException("SMS_CODE_EXPIRED");
        }
        if (!String.valueOf(binding.getOrDefault("deviceId", "")).equals(deviceId)) {
            throw new AccountSecurityRejectedException("DEVICE_MISMATCH");
        }
        String mobile = sms.consume(challengeId, code).mobile();
        storeVerified(userId, purpose, challengeId, deviceId, mobile);
        return mobile;
    }

    private String consumeEmailChallenge(UUID userId, String challengeId, String code) {
        String verified = verifiedTarget(userId, "EMAIL", challengeId, "");
        if (verified != null) return verified;
        String result = redis.execute(
                VERIFY_EMAIL_SCRIPT,
                java.util.List.of(CHALLENGE_PREFIX + challengeId, EMAIL_LOCK_PREFIX + userId),
                userId.toString(), digestHex(code == null ? "" : code.trim()),
                Integer.toString(MAX_ATTEMPTS), Long.toString(lockDuration.toSeconds()));
        if (result != null && result.startsWith("OK:")) {
            String email = result.substring(3);
            storeVerified(userId, "EMAIL", challengeId, "", email);
            return email;
        }
        if ("LOCKED".equals(result)) {
            throw new AccountSecurityRejectedException("EMAIL_CODE_LOCKED", lockDuration.toSeconds());
        }
        if ("INVALID".equals(result)) {
            throw new AccountSecurityRejectedException("EMAIL_CODE_INVALID");
        }
        throw new AccountSecurityRejectedException("EMAIL_CODE_EXPIRED");
    }

    private void storeVerified(
            UUID userId, String purpose, String challengeId, String deviceId, String target) {
        String key = VERIFIED_PREFIX + challengeId;
        redis.opsForHash().putAll(key, Map.of(
                "userId", userId.toString(),
                "purpose", purpose,
                "deviceId", deviceId,
                "target", target));
        redis.expire(key, Duration.ofMinutes(5));
    }

    private String verifiedTarget(
            UUID userId, String purpose, String challengeId, String deviceId) {
        Map<Object, Object> values = redis.opsForHash().entries(VERIFIED_PREFIX + challengeId);
        if (values.isEmpty()) return null;
        if (!userId.toString().equals(values.get("userId"))
                || !purpose.equals(values.get("purpose"))
                || !deviceId.equals(values.get("deviceId"))) {
            throw new AccountSecurityRejectedException("DEVICE_MISMATCH");
        }
        return (String) values.get("target");
    }

    private VerificationChallenge challengeReplay(
            UUID userId, String purpose, String idempotencyKey, byte[] requestHash) {
        String key = idempotencyRedisKey(userId, purpose, idempotencyKey);
        Map<Object, Object> values = redis.opsForHash().entries(key);
        if (values.isEmpty()) return null;
        ensureSameRequest(HexFormat.of().parseHex((String) values.get("requestHash")), requestHash);
        return new VerificationChallenge(
                (String) values.get("challengeId"),
                (String) values.get("maskedTarget"),
                Instant.parse((String) values.get("expiresAt")),
                Long.parseLong((String) values.get("resendAfterSeconds")));
    }

    private void storeChallengeIdempotency(
            UUID userId,
            String purpose,
            String idempotencyKey,
            byte[] requestHash,
            VerificationChallenge challenge) {
        String key = idempotencyRedisKey(userId, purpose, idempotencyKey);
        redis.opsForHash().putAll(key, Map.of(
                "requestHash", HexFormat.of().formatHex(requestHash),
                "challengeId", challenge.challengeId(),
                "maskedTarget", challenge.maskedTarget(),
                "expiresAt", challenge.expiresAt().toString(),
                "resendAfterSeconds", Long.toString(challenge.resendAfterSeconds())));
        redis.expireAt(key, challenge.expiresAt());
    }

    private String idempotencyRedisKey(UUID userId, String purpose, String idempotencyKey) {
        return IDEMPOTENCY_PREFIX + userId + ":" + purpose + ":" + idempotencyKey;
    }

    private boolean completed(
            UUID userId, String operationType, String idempotencyKey, byte[] requestHash) {
        Optional<OperationRow> operation = accounts.findOperation(userId, operationType, idempotencyKey);
        if (operation.isEmpty()) return false;
        ensureSameRequest(operation.get().requestHash(), requestHash);
        return true;
    }

    private PaymentPasswordVerification verificationResponse(VerificationRow row) {
        if (row.consumedAt() != null) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_USED");
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_EXPIRED");
        }
        return new PaymentPasswordVerification(
                tokens.issue(row.verificationId(), row.userId(), row.deviceId(),
                        row.issuedAt(), row.expiresAt()),
                row.expiresAt());
    }

    private void ensureSameRequest(byte[] stored, byte[] supplied) {
        if (!MessageDigest.isEqual(stored, supplied)) {
            throw new AccountSecurityRejectedException("IDEMPOTENCY_KEY_REUSED");
        }
    }

    private byte[] requestHash(String operation, String value) {
        return digest(operation + "\n" + value);
    }

    private String digestHex(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest account-security value", exception);
        }
    }

    private long ttl(String key) {
        Long seconds = redis.getExpire(key, TimeUnit.SECONDS);
        return seconds == null || seconds < 0 ? 0 : seconds;
    }

    private void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128) {
            throw new AccountSecurityRejectedException("DEVICE_INVALID");
        }
    }

    public record VerificationChallenge(
            String challengeId,
            String maskedTarget,
            Instant expiresAt,
            long resendAfterSeconds) {
    }

    public record PaymentPasswordVerification(String verificationToken, Instant expiresAt) {
    }
}
