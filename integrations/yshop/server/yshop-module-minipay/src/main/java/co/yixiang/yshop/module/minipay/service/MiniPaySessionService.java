package co.yixiang.yshop.module.minipay.service;

import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApi;
import co.yixiang.yshop.module.system.enums.oauth2.OAuth2ClientConstants;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MiniPaySessionService {
    private final JdbcTemplate jdbc;
    private final OAuth2TokenApi tokens;
    private final long tenantId;

    public MiniPaySessionService(
            JdbcTemplate jdbc,
            OAuth2TokenApi tokens,
            @Value("${yshop.minipay.tenant-id:1}") long tenantId) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.tenantId = tenantId;
    }

    public RevokeView revoke(String rawSubject) {
        String subject;
        try {
            subject = UUID.fromString(rawSubject).toString();
        } catch (Exception exception) {
            throw new MiniPayProblem("MINIPAY_SUBJECT_INVALID", HttpStatus.BAD_REQUEST);
        }
        List<Long> members = jdbc.query("""
                SELECT member_id FROM yshop_minipay_external_identity
                 WHERE provider = 'MINIPAY' AND subject = ?
                """, (rs, ignored) -> rs.getLong(1), subject);
        if (members.isEmpty()) {
            return new RevokeView(subject, 0, true);
        }
        Long previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            int revoked = tokens.removeAccessTokens(
                    members.get(0), UserTypeEnum.MEMBER.getValue(),
                    OAuth2ClientConstants.CLIENT_ID_MINIPAY_FOOD_H5);
            return new RevokeView(subject, revoked, true);
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
        }
    }

    public record RevokeView(String subject, int revokedAccessTokens, boolean revoked) { }
}
