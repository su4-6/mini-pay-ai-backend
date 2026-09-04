package com.minipay.agent.application.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class MemoryContentPolicy {
    private static final Pattern FULL_MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern JWT = Pattern.compile("(?i)(?:bearer\\s+)?eyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(支付密码|登录密码|密码|验证码|短信码|otp|token|cookie|api[_ -]?key|secret|私钥)\\s*[:：是为]?\\s*\\S{4,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern DETAILED_ADDRESS = Pattern.compile(
            "(?:路|街|巷|道|弄|小区|大厦|公寓|村|镇).{0,20}\\d{1,6}(?:号|栋|幢|单元|室)");
    private static final Pattern BILL_DETAIL = Pattern.compile(
            "(?:余额|账单|交易单号|流水号|订单号).{0,20}(?:¥|￥|\\d{2,}(?:\\.\\d{1,2})?)");

    public String validateAndNormalize(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 256) {
            reject("记忆内容必须为 1 到 256 个字符");
        }
        if (FULL_MOBILE.matcher(normalized).find()) reject("长期记忆不能保存完整手机号");
        if (JWT.matcher(normalized).find() || CREDENTIAL.matcher(normalized).find()
                || PRIVATE_KEY.matcher(normalized).find()) {
            reject("长期记忆不能保存密码、验证码、Token 或密钥");
        }
        if (DETAILED_ADDRESS.matcher(normalized).find()) reject("长期记忆不能保存详细门牌地址");
        if (BILL_DETAIL.matcher(normalized).find()) reject("长期记忆不能保存余额或逐笔交易明细");
        return normalized;
    }

    private static void reject(String message) {
        throw new AgentApplicationException("AGENT_MEMORY_VALUE_REJECTED", message);
    }
}
