package com.minipay.identity.application.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class CaptchaService {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Duration TTL = Duration.ofMinutes(2);
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;

    public CaptchaService(
            StringRedisTemplate redis,
            @Value("${minipay.identity.captcha-pepper}") String pepper) {
        this.redis = redis;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public CaptchaChallenge create() {
        String id = UUID.randomUUID().toString();
        String code = randomCode();
        redis.opsForValue().set(key(id), digest(code), TTL);
        redis.opsForValue().set(imageKey(id), Base64.getEncoder().encodeToString(render(code)), TTL);
        return new CaptchaChallenge(id, "/api/v1/auth/captchas/" + id + "/image", Instant.now().plus(TTL));
    }

    public byte[] image(String id) {
        String encoded = redis.opsForValue().get(imageKey(id));
        if (encoded == null) {
            throw new LoginRejectedException("CAPTCHA_EXPIRED");
        }
        return Base64.getDecoder().decode(encoded);
    }

    public void consume(String id, String answer) {
        String expected = id == null ? null : redis.opsForValue().getAndDelete(key(id));
        if (id != null) {
            redis.delete(imageKey(id));
        }
        String actual = digest(answer == null ? "" : answer.trim().toUpperCase());
        if (expected == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new LoginRejectedException("CAPTCHA_INVALID");
        }
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private byte[] render(String code) {
        try {
            BufferedImage image = new BufferedImage(128, 48, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(248, 250, 252));
            graphics.fillRect(0, 0, 128, 48);
            graphics.setStroke(new BasicStroke(1.2f));
            for (int index = 0; index < 7; index++) {
                graphics.setColor(new Color(148, 163, 184, 120));
                graphics.drawLine(random.nextInt(128), random.nextInt(48), random.nextInt(128), random.nextInt(48));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 29));
            for (int index = 0; index < code.length(); index++) {
                graphics.setColor(index % 2 == 0 ? new Color(15, 23, 42) : new Color(22, 119, 255));
                graphics.drawString(String.valueOf(code.charAt(index)), 13 + index * 28, 34 + random.nextInt(5));
            }
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to render captcha", exception);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest captcha", exception);
        }
    }

    private String key(String id) {
        return "minipay:auth:captcha:" + id;
    }

    private String imageKey(String id) {
        return "minipay:auth:captcha-image:" + id;
    }

    public record CaptchaChallenge(String captchaId, String imageUrl, Instant expiresAt) {
    }
}
