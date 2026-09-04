package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.PhoneNumberService;
import com.minipay.identity.application.service.AdminAuthenticationService;
import com.minipay.identity.application.service.AdminActionAuditService;
import com.minipay.identity.application.service.UuidV7;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/** System administration boundary. Responses contain masked identity data only. */
@RestController
@RequestMapping("/api/v1/admin")
public class SystemAdminController {
    private static final Set<String> SYSTEM_ROLES = Set.of(
            "system_super_admin", "system_account_admin", "system_auditor");
    private static final Set<String> CREATABLE_ROLES = Set.of(
            "platform_admin", "system_super_admin", "system_account_admin", "system_auditor");
    private final JdbcTemplate jdbc;
    private final PhoneNumberService phones;
    private final AdminAuthenticationService authentication;
    private final HttpServletRequest request;
    private final AdminActionAuditService audits;

    public SystemAdminController(JdbcTemplate jdbc, PhoneNumberService phones,
            AdminAuthenticationService authentication, HttpServletRequest request,
            AdminActionAuditService audits) {
        this.jdbc = jdbc;
        this.phones = phones;
        this.authentication = authentication;
        this.request = request;
        this.audits = audits;
    }

    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public AccountPage accounts(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String minipayNo,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role) {
        requireRead(auth);
        int safePage = Math.max(1, page); int safeSize = Math.max(1, Math.min(100, size));
        List<Object> args = new ArrayList<>();
        String where = where(userId, minipayNo, mobile, status, role, args);
        List<AccountView> items = jdbc.query("""
                SELECT u.user_id,u.minipay_no,u.nickname,u.phone_masked,u.status,u.credential_type,
                       u.onboarding_status,u.version,u.created_at,e.email_masked,
                       EXISTS(SELECT 1 FROM user_credential lc WHERE lc.user_id=u.user_id
                              AND lc.credential_type='LOGIN_PASSWORD' AND lc.status='ACTIVE') login_password_set,
                       EXISTS(SELECT 1 FROM user_credential pc WHERE pc.user_id=u.user_id
                              AND pc.credential_type='PAYMENT_PASSWORD' AND pc.status='ACTIVE') payment_password_set,
                       GROUP_CONCAT(r.role_code ORDER BY r.role_code) roles
                FROM user_profile u LEFT JOIN consumer_email_contact e ON e.user_id=u.user_id
                LEFT JOIN user_role r ON r.user_id=u.user_id
                """ + where + " GROUP BY u.user_id,u.minipay_no,u.nickname,u.phone_masked,u.status,u.credential_type,u.onboarding_status,u.version,u.created_at,e.email_masked"
                + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?", this::mapAccount,
                append(args, safeSize, (safePage - 1) * safeSize));
        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT u.user_id) FROM user_profile u " + where,
                Long.class, args.toArray());
        return new AccountPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public AdminSummary summary(JwtAuthenticationToken auth) {
        requireRead(auth);
        long consumers=count("SELECT COUNT(1) FROM user_profile WHERE onboarding_status='COMPLETED'");
        long merchantOwners=countRole("merchant_owner");
        long operators=countRole("platform_admin");
        long administrators=count("SELECT COUNT(DISTINCT user_id) FROM user_role WHERE role_code IN ('system_super_admin','system_account_admin','system_auditor')");
        return new AdminSummary(consumers,merchantOwners,operators,administrators);
    }

    @GetMapping("/accounts/{userId}")
    @Transactional(readOnly = true)
    public AccountView account(JwtAuthenticationToken auth, @PathVariable UUID userId) {
        requireRead(auth);
        return accounts(auth, 1, 1, userId, null, null, null, null).items().stream()
                .findFirst().orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
    }

    @PostMapping("/accounts/backoffice")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AccountView create(JwtAuthenticationToken auth,
            @RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId,
            @RequestHeader(value="X-Reason",defaultValue="create backoffice account") String reason,
            @Valid @RequestBody CreateAccount body) {
        UUID actor = requireSuper(auth); requireRole(body.role());
        Optional<String> replay = replay(actor, key, "BACKOFFICE_CREATE");
        if (replay.isPresent()) return account(auth, UUID.fromString(replay.get()));
        String mobile = phones.normalize(body.mobile()); byte[] phoneHash = phones.hash(mobile);
        if (jdbc.queryForObject("SELECT COUNT(1) FROM user_profile WHERE phone_hash=?", Long.class, phoneHash) > 0) {
            throw problem(HttpStatus.CONFLICT, "MOBILE_ALREADY_BOUND");
        }
        UUID id = UuidV7.generate(); Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO user_profile(user_id,login_name,minipay_no,phone_hash,phone_masked,nickname,
                  avatar_object_key,status,credential_type,onboarding_status,onboarding_completed_at,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,NULL,'ACTIVE','SMS','COMPLETED',?,0,?,?)
                """, bytes(id), "backoffice-" + id, "MP" + id.toString().replace("-", "").substring(0,20).toUpperCase(),
                phoneHash, phones.mask(mobile), body.displayName().trim(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO user_role(user_id,role_code,created_at) VALUES(?,?,?)", bytes(id), body.role(), Timestamp.from(now));
        audit(actor,"BACKOFFICE_CREATE","ACCOUNT",id.toString(),"SUCCEEDED",decodeReason(reason),requestId);
        remember(actor,key,"BACKOFFICE_CREATE",id.toString());
        return account(auth,id);
    }

    @PutMapping("/accounts/{userId}/status")
    @Transactional
    public AccountView status(JwtAuthenticationToken auth, @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Reason") String reason,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId,
            @Valid @RequestBody StatusChange body) {
        UUID actor=requireWriter(auth); AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"ACCOUNT_STATUS").isPresent()) return account(auth,userId);
        int changed=jdbc.update("UPDATE user_profile SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND version=?",
                body.status(),bytes(userId),body.version());
        if(changed!=1) throw problem(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CONFLICT");
        audit(actor,"ACCOUNT_STATUS","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason),requestId); remember(actor,key,"ACCOUNT_STATUS",userId.toString());
        return account(auth,userId);
    }

    @PostMapping("/accounts/{userId}/unlock")
    @Transactional
    public AccountView unlock(JwtAuthenticationToken auth,@PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-Reason") String reason,
            @RequestHeader("If-Match") long version,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId) {
        UUID actor=requireWriter(auth); AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"ACCOUNT_UNLOCK").isPresent()) return account(auth,userId);
        bumpVersion(userId,version);
        jdbc.update("UPDATE user_credential SET failed_attempts=0,locked_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND credential_type='LOGIN_PASSWORD'",bytes(userId));
        audit(actor,"ACCOUNT_UNLOCK","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason),requestId); remember(actor,key,"ACCOUNT_UNLOCK",userId.toString()); return account(auth,userId);
    }

    @PostMapping("/accounts/{userId}/credential-reset")
    @Transactional
    public AccountView reset(JwtAuthenticationToken auth,@PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-Reason") String reason,
            @RequestHeader("If-Match") long version,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId) {
        UUID actor=requireWriter(auth); AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"CREDENTIAL_RESET").isPresent()) return account(auth,userId);
        jdbc.update("DELETE FROM user_credential WHERE user_id=? AND credential_type='LOGIN_PASSWORD'",bytes(userId));
        int changed=jdbc.update("UPDATE user_profile SET credential_type='SMS',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND version=?",bytes(userId),version);
        if(changed!=1) throw problem(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CONFLICT");
        revoke(userId); audit(actor,"CREDENTIAL_RESET","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason),requestId); remember(actor,key,"CREDENTIAL_RESET",userId.toString()); return account(auth,userId);
    }

    @PostMapping("/accounts/{userId}/payment-password-reset")
    @Transactional
    public AccountView resetPaymentPassword(JwtAuthenticationToken auth,@PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-Reason") String reason,
            @RequestHeader("If-Match") long version,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId) {
        UUID actor=requireWriter(auth); AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"PAYMENT_PASSWORD_RESET").isPresent()) return account(auth,userId);
        int removed=jdbc.update("DELETE FROM user_credential WHERE user_id=? AND credential_type='PAYMENT_PASSWORD'",bytes(userId));
        bumpVersion(userId,version);
        revoke(userId);
        audit(actor,"PAYMENT_PASSWORD_RESET","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason)+"; removed="+removed,requestId);
        remember(actor,key,"PAYMENT_PASSWORD_RESET",userId.toString());
        return account(auth,userId);
    }

    @PostMapping("/accounts/{userId}/sessions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revokeSessions(JwtAuthenticationToken auth,@PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-Reason") String reason,
            @RequestHeader("If-Match") long version,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId) {
        UUID actor=requireWriter(auth); AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"SESSIONS_REVOKE").isPresent()) return;
        bumpVersion(userId,version);
        revoke(userId); audit(actor,"SESSIONS_REVOKE","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason),requestId); remember(actor,key,"SESSIONS_REVOKE",userId.toString());
    }

    @PutMapping("/accounts/{userId}/role")
    @Transactional
    public AccountView role(JwtAuthenticationToken auth, @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Reason") String reason,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId,
            @Valid @RequestBody RoleChange body) {
        UUID actor=requireSuper(auth); requireRole(body.role());
        AccountView target=account(auth,userId); guardTarget(auth,actor,target,userId);
        if (replay(actor,key,"BACKOFFICE_ROLE_CHANGE").isPresent()) return account(auth,userId);
        int changed=jdbc.update("UPDATE user_profile SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND version=?",
                bytes(userId),body.version());
        if(changed!=1) throw problem(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CONFLICT");
        jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_code IN ('platform_admin','system_super_admin','system_account_admin','system_auditor')",bytes(userId));
        jdbc.update("INSERT INTO user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",bytes(userId),body.role());
        revoke(userId);
        audit(actor,"BACKOFFICE_ROLE_CHANGE","ACCOUNT",userId.toString(),"SUCCEEDED",decodeReason(reason),requestId);
        remember(actor,key,"BACKOFFICE_ROLE_CHANGE",userId.toString());
        return account(auth,userId);
    }

    @PutMapping("/me/password")
    @Transactional
    public AdminAuthenticationService.PasswordStatus ownPassword(JwtAuthenticationToken auth,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Request-Id",defaultValue="unknown") String requestId,
            @Valid @RequestBody OwnPassword body) {
        UUID actor=requireRead(auth);
        if (replay(actor,key,"OWN_PASSWORD_SET").isPresent())
            return new AdminAuthenticationService.PasswordStatus(true, Instant.now());
        AdminAuthenticationService.PasswordStatus result=authentication.setLoginPassword(actor,body.newPassword());
        revoke(actor);
        audit(actor,"OWN_PASSWORD_SET","ACCOUNT",actor.toString(),"SUCCEEDED","self service",requestId);
        remember(actor,key,"OWN_PASSWORD_SET",actor.toString());
        return result;
    }

    @GetMapping("/action-audits")
    @Transactional(readOnly=true)
    public AuditPage audits(JwtAuthenticationToken auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID actorUserId,@RequestParam(required=false) String action,
            @RequestParam(required=false) String targetType,@RequestParam(required=false) String result,
            @RequestParam(required=false) String requestId,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant to){
        requireRead(auth); int p=Math.max(1,page),s=Math.max(1,Math.min(100,size));
        AuditQuery filter=auditQuery(actorUserId,action,targetType,result,requestId,from,to);
        List<Object> pageArgs=new ArrayList<>(filter.args());pageArgs.add(s);pageArgs.add((p-1)*s);
        List<AuditView> items=jdbc.query("SELECT audit_id,actor_user_id,action_code,target_type,target_id,result_code,reason,request_id,occurred_at FROM admin_action_audit"+filter.where()+" ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                (rs,n)->new AuditView(AdminAccountRepository.bytesToUuid(rs.getBytes("audit_id")).toString(),rs.getBytes("actor_user_id")==null?null:AdminAccountRepository.bytesToUuid(rs.getBytes("actor_user_id")).toString(),rs.getString("action_code"),rs.getString("target_type"),rs.getString("target_id"),rs.getString("result_code"),rs.getString("reason"),rs.getString("request_id"),rs.getTimestamp("occurred_at").toInstant()),pageArgs.toArray());
        Long total=jdbc.queryForObject("SELECT COUNT(1) FROM admin_action_audit"+filter.where(),Long.class,filter.args().toArray()); return new AuditPage(items,p,s,total==null?0:total);
    }

    static AuditQuery auditQuery(UUID actorUserId,String action,String targetType,String result,String requestId,Instant from,Instant to){
        StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();
        if(actorUserId!=null){where.append(" AND actor_user_id=?");args.add(bytes(actorUserId));}
        appendAuditText(where,args,"action_code",action);appendAuditText(where,args,"target_type",targetType);
        appendAuditText(where,args,"result_code",result);appendAuditText(where,args,"request_id",requestId);
        if(from!=null){where.append(" AND occurred_at>=?");args.add(java.sql.Timestamp.from(from));}
        if(to!=null){where.append(" AND occurred_at<=?");args.add(java.sql.Timestamp.from(to));}
        return new AuditQuery(where.toString(),args);
    }
    private static void appendAuditText(StringBuilder where,List<Object> args,String column,String value){if(value!=null&&!value.isBlank()){where.append(" AND ").append(column).append("=?");args.add(value.trim());}}

    private String where(UUID userId,String no,String mobile,String status,String role,List<Object> args){
        StringBuilder w=new StringBuilder(" WHERE 1=1");
        if(userId!=null){w.append(" AND u.user_id=?");args.add(bytes(userId));}
        if(no!=null&&!no.isBlank()){w.append(" AND u.minipay_no=?");args.add(no.trim());}
        if(mobile!=null&&!mobile.isBlank()){w.append(" AND u.phone_hash=?");args.add(phones.hash(phones.normalize(mobile)));}
        if(status!=null&&!status.isBlank()){w.append(" AND u.status=?");args.add(status.trim());}
        if(role!=null&&!role.isBlank()){w.append(" AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.user_id AND ur.role_code=?)");args.add(role.trim());}
        return w.toString();
    }
    private AccountView mapAccount(java.sql.ResultSet rs,int row)throws java.sql.SQLException{String raw=rs.getString("roles");List<String> roles=raw==null?List.of():Arrays.asList(raw.split(","));return new AccountView(AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")).toString(),rs.getString("minipay_no"),rs.getString("nickname"),rs.getString("phone_masked"),rs.getString("email_masked"),rs.getString("status"),rs.getString("credential_type"),rs.getString("onboarding_status"),rs.getBoolean("login_password_set"),rs.getBoolean("payment_password_set"),roles,rs.getLong("version"),rs.getTimestamp("created_at").toInstant());}
    private UUID requireRead(JwtAuthenticationToken a){UUID id=UUID.fromString(a.getName());if(roles(a).stream().noneMatch(SYSTEM_ROLES::contains))throw problem(HttpStatus.FORBIDDEN,"ADMIN_ROLE_REQUIRED");return id;}
    private UUID requireWriter(JwtAuthenticationToken a){UUID id=requireRead(a);if(!roles(a).contains("system_super_admin")&&!roles(a).contains("system_account_admin"))throw problem(HttpStatus.FORBIDDEN,"ADMIN_WRITE_FORBIDDEN");return id;}
    private UUID requireSuper(JwtAuthenticationToken a){UUID id=requireRead(a);if(!roles(a).contains("system_super_admin"))throw problem(HttpStatus.FORBIDDEN,"SUPER_ADMIN_REQUIRED");return id;}
    private List<String> roles(JwtAuthenticationToken a){List<String> r=a.getToken().getClaimAsStringList("roles");return r==null?List.of():r;}
    private void guardTarget(JwtAuthenticationToken auth,UUID actor,AccountView target,UUID targetId){boolean superAdmin=roles(auth).contains("system_super_admin");boolean systemTarget=target.roles().stream().anyMatch(SYSTEM_ROLES::contains);if(systemTarget&&!superAdmin)throw problem(HttpStatus.FORBIDDEN,"SYSTEM_ADMIN_PROTECTED");if(actor.equals(targetId))throw problem(HttpStatus.CONFLICT,"SELF_ADMIN_MUTATION_FORBIDDEN");if(target.roles().contains("system_super_admin")){Long count=jdbc.queryForObject("SELECT COUNT(DISTINCT u.user_id) FROM user_profile u JOIN user_role r ON r.user_id=u.user_id WHERE u.status='ACTIVE' AND r.role_code='system_super_admin'",Long.class);if(count!=null&&count<=1)throw problem(HttpStatus.CONFLICT,"LAST_SUPER_ADMIN_PROTECTED");}}
    private void requireRole(String role){if(!CREATABLE_ROLES.contains(role))throw problem(HttpStatus.BAD_REQUEST,"INVALID_BACKOFFICE_ROLE");}
    private void revoke(UUID id){jdbc.update("DELETE FROM oauth2_refresh_token_family WHERE authorization_id IN (SELECT id FROM oauth2_authorization WHERE principal_name=?)",id.toString());jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name=?",id.toString());}
    private void audit(UUID actor,String action,String type,String target,String result,String reason,String requestId){audits.record(actor,action,type,target,result,reason,requestId,request.getHeader("X-Admin-Client-IP"),request.getHeader("User-Agent"));}
    private void remember(UUID actor,String key,String action,String target){try{jdbc.update("INSERT INTO admin_idempotency(actor_user_id,idempotency_key,action_code,target_id,completed_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6))",bytes(actor),key,action,target);}catch(org.springframework.dao.DuplicateKeyException ignored){}}
    private Optional<String> replay(UUID actor,String key,String action){List<String> targets=jdbc.query("SELECT target_id FROM admin_idempotency WHERE actor_user_id=? AND idempotency_key=? AND action_code=?",(rs,n)->rs.getString(1),bytes(actor),key,action);return targets.stream().findFirst();}
    private long count(String sql){Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value;}
    private long countRole(String role){Long value=jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM user_role WHERE role_code=?",Long.class,role);return value==null?0:value;}
    private void bumpVersion(UUID userId,long version){int changed=jdbc.update("UPDATE user_profile SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND version=?",bytes(userId),version);if(changed!=1)throw problem(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CONFLICT");}
    private Object[] append(List<Object> args,Object...more){List<Object> copy=new ArrayList<>(args);copy.addAll(List.of(more));return copy.toArray();}
    private static byte[] bytes(UUID id){return AdminAccountRepository.uuidToBytes(id);}
    private static ResponseStatusException problem(HttpStatus status,String code){return new ResponseStatusException(status,code);}
    static String decodeReason(String encoded) {
        final String decoded;
        try {
            decoded = UriUtils.decode(encoded, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException exception) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_REASON_ENCODING");
        }
        if (decoded.length() < 3 || decoded.length() > 200) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_REASON");
        }
        return decoded;
    }
    public record AccountView(String userId,String minipayNo,String displayName,String maskedMobile,String maskedEmail,String status,String credentialType,String onboardingStatus,boolean loginPasswordSet,boolean paymentPasswordSet,List<String> roles,long version,Instant createdAt){}
    public record AccountPage(List<AccountView> items,int page,int size,long total){}
    public record AdminSummary(long consumers,long merchantOwners,long operators,long administrators){}
    public record AuditView(String auditId,String actorUserId,String action,String targetType,String targetId,String result,String reason,String requestId,Instant occurredAt){}
    public record AuditPage(List<AuditView> items,int page,int size,long total){}
    record AuditQuery(String where,List<Object> args){}
    public record CreateAccount(@Pattern(regexp="^1[3-9]\\d{9}$") String mobile,@NotBlank @Size(max=64) String displayName,@NotBlank String role){}
    public record StatusChange(@Pattern(regexp="ACTIVE|DISABLED") String status,long version){}
    public record RoleChange(@NotBlank String role,long version){}
    public record OwnPassword(@NotBlank String newPassword){}
}
