package com.minipay.agent.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class SensitiveInputSanitizer {
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern JWT = Pattern.compile("(?i)(?:bearer\\s+)?eyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(支付密码|登录密码|密码|验证码|短信码|otp|one[- ]?time code)\\s*[:：是为]?\\s*[a-zA-Z0-9]{4,64}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    public SanitizedInput sanitize(String input) {
        if (input == null || input.isBlank()) {
            throw new AgentApplicationException("AGENT_MESSAGE_EMPTY", "消息不能为空");
        }
        if (JWT.matcher(input).find() || CREDENTIAL.matcher(input).find() || PRIVATE_KEY.matcher(input).find()) {
            throw new AgentApplicationException(
                    "AGENT_SENSITIVE_CREDENTIAL_REJECTED",
                    "请勿在对话中输入密码、验证码或令牌，请使用原生安全页面完成授权");
        }

        Matcher matcher = MOBILE.matcher(input);
        List<String> mobiles = new ArrayList<>(2);
        while (matcher.find()) {
            mobiles.add(matcher.group(1));
        }
        if (mobiles.size() > 1) {
            throw new AgentApplicationException(
                    "AGENT_MULTIPLE_MOBILES_UNSUPPORTED",
                    "一次只能精确解析一个完整手机号");
        }

        String sanitized = matcher.reset().replaceAll("[MOBILE_EXACT]").trim();
        if (sanitized.length() > 1000) {
            throw new AgentApplicationException("AGENT_MESSAGE_TOO_LONG", "消息长度不能超过 1000 个字符");
        }
        return new SanitizedInput(sanitized, mobiles.stream().findFirst());
    }

    public record SanitizedInput(String text, Optional<String> exactMobile) {
        public SanitizedInput {
            text = text.strip();
            exactMobile = exactMobile == null ? Optional.empty() : exactMobile;
        }

        public boolean containsExactMobile() {
            return exactMobile.isPresent();
        }
    }
}
