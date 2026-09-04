package com.minipay.identity.infrastructure.config;

import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.application.service.UuidV7;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Optional, idempotent first-super-admin bootstrap. No raw mobile is logged. */
@Component
public class SystemAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final PhoneNumberService phones;
    private final String mobile; private final String displayName;
    public SystemAdminBootstrap(JdbcTemplate jdbc, PhoneNumberService phones,
            @Value("${minipay.identity.bootstrap-admin.mobile:}") String mobile,
            @Value("${minipay.identity.bootstrap-admin.display-name:System Administrator}") String displayName) {
        this.jdbc=jdbc;this.phones=phones;this.mobile=mobile;this.displayName=displayName;
    }
    @Override public void run(ApplicationArguments args) {
        if (mobile == null || mobile.isBlank()) return;
        Long existing = jdbc.queryForObject("SELECT COUNT(1) FROM user_role WHERE role_code='system_super_admin'",Long.class);
        if (existing != null && existing > 0) return;
        String normalized=phones.normalize(mobile); byte[] hash=phones.hash(normalized);
        Long bound=jdbc.queryForObject("SELECT COUNT(1) FROM user_profile WHERE phone_hash=?",Long.class,hash);
        if(bound!=null&&bound>0) throw new IllegalStateException("Bootstrap administrator mobile is already bound");
        UUID id= UuidV7.generate(); Instant now=Instant.now(); byte[] bytes= AdminAccountRepository.uuidToBytes(id);
        jdbc.update("""
                INSERT INTO user_profile(user_id,login_name,minipay_no,phone_hash,phone_masked,nickname,
                  avatar_object_key,status,credential_type,onboarding_status,onboarding_completed_at,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,NULL,'ACTIVE','SMS','COMPLETED',?,0,?,?)
                """,bytes,"system-admin-"+id,"MP"+id.toString().replace("-","").substring(0,20).toUpperCase(),hash,
                phones.mask(normalized),displayName,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        jdbc.update("INSERT INTO user_role(user_id,role_code,created_at) VALUES(?,'system_super_admin',?)",bytes,Timestamp.from(now));
        jdbc.update("INSERT INTO admin_action_audit(audit_id,actor_user_id,action_code,target_type,target_id,result_code,reason,request_id,occurred_at) VALUES(?,NULL,'SYSTEM_BOOTSTRAP','ACCOUNT',?,'SUCCEEDED','initial bootstrap','bootstrap',?)",
                AdminAccountRepository.uuidToBytes(UuidV7.generate()),id.toString(),Timestamp.from(now));
    }
}
