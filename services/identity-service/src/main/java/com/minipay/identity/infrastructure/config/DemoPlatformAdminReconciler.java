package com.minipay.identity.infrastructure.config;

import com.minipay.identity.application.service.PhoneNumberService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps the demo operator lookup hash aligned with the environment-specific phone pepper. */
@Component
@Profile("demo-auth")
public class DemoPlatformAdminReconciler implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PhoneNumberService phones;
    private final String mobile;

    public DemoPlatformAdminReconciler(
            JdbcTemplate jdbc,
            PhoneNumberService phones,
            @Value("${minipay.identity.demo-platform-admin.mobile:13800138000}") String mobile) {
        this.jdbc = jdbc;
        this.phones = phones;
        this.mobile = mobile;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalized = phones.normalize(mobile);
        jdbc.update("""
                UPDATE user_profile
                   SET phone_hash=?, phone_masked=?, updated_at=UTC_TIMESTAMP(6)
                 WHERE login_name='ops-admin-demo'
                """, phones.hash(normalized), phones.mask(normalized));
    }
}
