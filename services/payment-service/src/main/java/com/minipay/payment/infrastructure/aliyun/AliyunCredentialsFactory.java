package com.minipay.payment.infrastructure.aliyun;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;

/**
 * Builds an Alibaba Cloud credential client without persisting credentials.
 *
 * <p>Explicit properties are retained for local compatibility. When none are
 * supplied, the official default provider chain resolves environment, OIDC,
 * profile, ECS/ECI RAM role, or Credentials URI credentials.</p>
 */
public final class AliyunCredentialsFactory {
    private AliyunCredentialsFactory() {
    }

    public static Client create(
            String ramRoleName,
            String accessKeyId,
            String accessKeySecret,
            String securityToken) {
        String role = normalized(ramRoleName);
        String id = normalized(accessKeyId);
        String secret = normalized(accessKeySecret);
        String token = normalized(securityToken);

        if (role != null) {
            return new Client(new Config()
                    .setType("ecs_ram_role")
                    .setRoleName(role)
                    .setEnableIMDSv2(true));
        }
        if ((id == null) != (secret == null)) {
            throw new IllegalStateException("Alibaba Cloud AccessKey configuration is incomplete");
        }
        if (id != null) {
            Config config = new Config()
                    .setType(token == null ? "access_key" : "sts")
                    .setAccessKeyId(id)
                    .setAccessKeySecret(secret);
            if (token != null) {
                config.setSecurityToken(token);
            }
            return new Client(config);
        }
        return new Client();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
