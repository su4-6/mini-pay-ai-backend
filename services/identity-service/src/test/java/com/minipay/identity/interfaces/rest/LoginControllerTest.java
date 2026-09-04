package com.minipay.identity.interfaces.rest;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minipay.identity.application.service.AdminAuthenticationService;
import com.minipay.identity.application.service.AuthRateLimitService;
import com.minipay.identity.application.service.CaptchaService;
import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.SmsChallengeService;
import com.minipay.identity.domain.model.AdminPrincipal;
import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginControllerTest {
    private static final String PHONE = "13800138000";
    private static final String PASSWORD = "MiniPay@123456";

    private final AdminAuthenticationService authentication = mock(AdminAuthenticationService.class);
    private final CaptchaService captchas = mock(CaptchaService.class);
    private final SmsChallengeService smsChallenges = mock(SmsChallengeService.class);
    private final AuthRateLimitService rateLimits = mock(AuthRateLimitService.class);
    private final RequestCache requestCache = mock(RequestCache.class);
    private final LoginAuditRepository audits = mock(LoginAuditRepository.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LoginController controller = new LoginController(
                authentication,
                captchas,
                smsChallenges,
                rateLimits,
                requestCache,
                audits,
                "http://localhost:8000",
                "http://localhost:8002",
                "http://localhost:8001/login");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ajaxPasswordLoginReturnsRedirectWithoutEchoingCredentials() throws Exception {
        when(authentication.authenticatePassword(anyString(), anyString(), any()))
                .thenReturn(new AdminPrincipal(UUID.randomUUID(), "演示管理员", "not-returned"));

        mockMvc.perform(passwordLogin()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Request-Id", "request-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.redirectUrl").value("http://localhost:8000"))
                .andExpect(content().string(not(containsString(PHONE))))
                .andExpect(content().string(not(containsString(PASSWORD))));
        verify(audits).appendLogin(
                any(), eq(PHONE), eq("PASSWORD"), eq("SUCCESS"),
                any(), any(), eq("request-1"));
        verifyNoMoreInteractions(audits);
    }

    @Test
    void systemAdministratorRoleDoesNotHijackAnOperationsLogin() throws Exception {
        when(authentication.authenticatePassword(anyString(), anyString(), any()))
                .thenReturn(new AdminPrincipal(
                        UUID.randomUUID(), "System Administrator", "not-returned",
                        List.of("system_super_admin")));

        mockMvc.perform(passwordLogin()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Request-Id", "request-system-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value("http://localhost:8000"));
    }

    @Test
    void savedAdminAuthorizationRequestWinsOverTheDefaultPortal() throws Exception {
        when(authentication.authenticatePassword(anyString(), anyString(), any()))
                .thenReturn(new AdminPrincipal(
                        UUID.randomUUID(), "System Administrator", "not-returned",
                        List.of("system_super_admin")));
        org.springframework.security.web.savedrequest.SavedRequest savedRequest =
                mock(org.springframework.security.web.savedrequest.SavedRequest.class);
        when(savedRequest.getRedirectUrl())
                .thenReturn("http://localhost:8089/oauth2/authorization/minipay-admin");
        when(requestCache.getRequest(any(), any())).thenReturn(savedRequest);

        mockMvc.perform(passwordLogin()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Request-Id", "request-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl")
                        .value("http://localhost:8089/oauth2/authorization/minipay-admin"));
    }

    @Test
    void ajaxFailureReturnsGenericProblemDetailsWithoutEchoingCredentials() throws Exception {
        doThrow(new LoginRejectedException("CAPTCHA_INVALID"))
                .when(captchas).consume("captcha-id", "ABCD");

        mockMvc.perform(passwordLogin()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Request-Id", "request-2"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LOGIN_REJECTED"))
                .andExpect(jsonPath("$.detail").value("登录失败，请检查手机号、密码或验证码"))
                .andExpect(jsonPath("$.requestId").value("request-2"))
                .andExpect(content().string(not(containsString(PHONE))))
                .andExpect(content().string(not(containsString(PASSWORD))));
        verify(audits).appendLogin(
                isNull(), eq(PHONE), eq("PASSWORD"), eq("CAPTCHA_FAILED"),
                any(), any(), eq("request-2"));
        verifyNoMoreInteractions(audits);
    }

    @Test
    void nativeFormKeepsRedirectFallback() throws Exception {
        doThrow(new LoginRejectedException("LOGIN_REJECTED"))
                .when(captchas).consume("captcha-id", "ABCD");

        mockMvc.perform(passwordLogin())
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location", "http://localhost:8000/login?mode=password&error=1"));
    }

    @Test
    void loginPageRedirectsToTheIndependentOpsFrontend() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8000/login"));
    }

    @Test
    void browserLogoutInvalidatesIdentitySessionAndUsesOnlyKnownPortalTargets() throws Exception {
        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("identity-session", "active");
        mockMvc.perform(get("/session/logout").param("target", "admin").session(adminSession))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8002"));
        org.assertj.core.api.Assertions.assertThat(adminSession.isInvalid()).isTrue();

        mockMvc.perform(get("/session/logout").param("target", "https://evil.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8000"));

        mockMvc.perform(get("/session/logout").param("target", "merchant"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8001/login"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder passwordLogin() {
        return post("/login/password")
                .param("phone", PHONE)
                .param("password", PASSWORD)
                .param("captchaId", "captcha-id")
                .param("captchaCode", "ABCD");
    }
}
