package com.minipay.identity.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.PhoneNumberService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoPlatformAdminReconcilerTest {
    @Test
    void alignsDemoOperatorHashWithConfiguredPhonePepper() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PhoneNumberService phones = mock(PhoneNumberService.class);
        ApplicationArguments arguments = mock(ApplicationArguments.class);
        byte[] hash = new byte[] {1, 2, 3};
        when(phones.normalize("13800138000")).thenReturn("13800138000");
        when(phones.hash("13800138000")).thenReturn(hash);
        when(phones.mask("13800138000")).thenReturn("138****8000");

        new DemoPlatformAdminReconciler(jdbc, phones, "13800138000").run(arguments);

        verify(jdbc).update("""
                UPDATE user_profile
                   SET phone_hash=?, phone_masked=?, updated_at=UTC_TIMESTAMP(6)
                 WHERE login_name='ops-admin-demo'
                """, hash, "138****8000");
    }
}
