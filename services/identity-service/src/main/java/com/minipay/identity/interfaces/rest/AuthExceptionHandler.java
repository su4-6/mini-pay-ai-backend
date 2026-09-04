package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.MerchantOwnerRejectedException;
import com.minipay.identity.application.service.OnboardingRejectedException;
import com.minipay.identity.interfaces.rest.FriendRequestController.FriendRequestRejectedException;
import com.minipay.identity.application.service.PaymentAuthorizationRejectedException;
import com.minipay.identity.application.service.ProfileRejectedException;
import com.minipay.identity.application.service.RealNameVerificationRejectedException;
import com.minipay.identity.application.service.AccountSecurityRejectedException;
import com.minipay.identity.application.service.TransferRecipientLookupException;
import com.minipay.identity.application.service.ApplicationAuthorizationException;
import com.minipay.identity.infrastructure.sms.SmsDeliveryUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(ApplicationAuthorizationException.class)
    ResponseEntity<ProblemDetail> applicationAuthorization(
            ApplicationAuthorizationException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "APPLICATION_NOT_FOUND", "CONSUMER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "APPLICATION_AUTHORIZATION_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "PHONE_UPGRADE_REQUIRED", "APPLICATION_PHONE_VERIFICATION_REQUIRED" ->
                    HttpStatus.PRECONDITION_REQUIRED;
            case "SMS_RESEND_TOO_SOON", "AUTH_RATE_LIMITED", "SMS_RATE_LIMITED" ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case "SMS_LOCKED" -> HttpStatus.LOCKED;
            case "SMS_INVALID", "SMS_EXPIRED", "MOBILE_INVALID" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, exception.code(), "应用授权操作未完成", request);
    }
    @ExceptionHandler(TransferRecipientLookupException.class)
    ResponseEntity<ProblemDetail> transferRecipient(
            TransferRecipientLookupException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "TRANSFER_RECIPIENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SELF_TRANSFER_NOT_ALLOWED" -> HttpStatus.CONFLICT;
            case "RECIPIENT_LOOKUP_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "RECIPIENT_LOOKUP_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        String detail = switch (exception.code()) {
            case "MOBILE_INVALID" -> "请输入正确的中国大陆手机号";
            case "SELF_TRANSFER_NOT_ALLOWED" -> "不能向自己的账户转账";
            case "RECIPIENT_LOOKUP_RATE_LIMITED" -> "查询过于频繁，请稍后重试";
            case "RECIPIENT_LOOKUP_UNAVAILABLE" -> "收款账户查询暂不可用，请稍后重试";
            default -> "未找到可转账的 MiniPay 账户";
        };
        return response(status, exception.code(), detail, request);
    }

    @ExceptionHandler(AccountSecurityRejectedException.class)
    ResponseEntity<ProblemDetail> accountSecurity(AccountSecurityRejectedException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "MOBILE_ALREADY_BOUND", "EMAIL_ALREADY_BOUND", "IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "EMAIL_RESEND_TOO_SOON" -> HttpStatus.TOO_MANY_REQUESTS;
            case "EMAIL_CODE_LOCKED" -> HttpStatus.LOCKED;
            case "DEVICE_MISMATCH" -> HttpStatus.FORBIDDEN;
            case "EMAIL_DELIVERY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CONSUMER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, exception.code(), "账户安全信息操作未完成", request,
                exception.retryAfterSeconds());
    }
    @ExceptionHandler(LoginRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(LoginRejectedException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "AUTH_RATE_LIMITED", "SMS_RATE_LIMITED", "SMS_RESEND_TOO_SOON" ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case "SMS_LOCKED" -> HttpStatus.LOCKED;
            case "ACCOUNT_DISABLED" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        String code = switch (exception.code()) {
            case "MOBILE_INVALID" -> "MOBILE_INVALID";
            case "SMS_INVALID" -> "SMS_CODE_INVALID";
            case "SMS_EXPIRED" -> "SMS_CODE_EXPIRED";
            case "SMS_LOCKED" -> "SMS_CODE_LOCKED";
            case "CAPTCHA_INVALID" -> "CAPTCHA_INVALID";
            case "CAPTCHA_EXPIRED" -> "CAPTCHA_EXPIRED";
            case "SMS_RESEND_TOO_SOON" -> "SMS_RESEND_TOO_SOON";
            case "AUTH_RATE_LIMITED", "SMS_RATE_LIMITED" -> "AUTH_RATE_LIMITED";
            case "ACCOUNT_DISABLED" -> "ACCOUNT_DISABLED";
            case "PKCE_INVALID", "OAUTH_CLIENT_INVALID" -> "OAUTH_REQUEST_INVALID";
            default -> "AUTHENTICATION_CHALLENGE_REJECTED";
        };
        String detail = switch (code) {
            case "MOBILE_INVALID" -> "请输入正确的中国大陆手机号";
            case "SMS_CODE_INVALID" -> "验证码错误，请重新输入";
            case "SMS_CODE_EXPIRED" -> "验证码已过期，请重新获取";
            case "SMS_CODE_LOCKED" -> "尝试次数过多，请十分钟后重试";
            case "CAPTCHA_INVALID" -> "图形验证码错误，请重新输入";
            case "CAPTCHA_EXPIRED" -> "图形验证码已过期，请刷新后重试";
            case "AUTH_RATE_LIMITED" -> "操作过于频繁，请稍后重试";
            case "SMS_RESEND_TOO_SOON" -> "验证码发送过于频繁，请稍后重试";
            case "ACCOUNT_DISABLED" -> "账号当前不可用，请联系支持";
            case "OAUTH_REQUEST_INVALID" -> "登录请求无效，请重新发起";
            default -> "验证码无效或已过期，请重试";
        };
        return response(status, code, detail, request, exception.retryAfterSeconds());
    }

    @ExceptionHandler(SmsDeliveryUnavailableException.class)
    ResponseEntity<ProblemDetail> smsUnavailable(
            SmsDeliveryUnavailableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SMS_DELIVERY_UNAVAILABLE",
                "短信服务暂不可用，请稍后重试", request);
    }

    @ExceptionHandler(OnboardingRejectedException.class)
    ResponseEntity<ProblemDetail> onboarding(
            OnboardingRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "IDEMPOTENCY_KEY_REUSED", "ONBOARDING_ALREADY_COMPLETED",
                    "PAY_PASSWORD_ALREADY_SET" -> HttpStatus.CONFLICT;
            case "CONSUMER_NOT_FOUND", "AVATAR_UPLOAD_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AVATAR_UPLOAD_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String detail = switch (exception.code()) {
            case "NICKNAME_INVALID" -> "昵称须为 2 至 20 个字符";
            case "AVATAR_UPLOAD_EXPIRED" -> "头像上传凭证已过期";
            case "AVATAR_UPLOAD_FORBIDDEN", "AVATAR_UPLOAD_NOT_FOUND" -> "头像上传记录不可用";
            case "PAY_PASSWORD_INVALID" -> "支付密码必须为 6 位数字";
            case "IDEMPOTENCY_KEY_REUSED" -> "同一幂等键不能用于不同的初始化请求";
            case "ONBOARDING_ALREADY_COMPLETED", "PAY_PASSWORD_ALREADY_SET" ->
                    "首次初始化已完成，请使用资料或支付密码修改流程";
            case "CONSUMER_NOT_FOUND" -> "未找到当前消费者";
            default -> "首次资料初始化失败";
        };
        return response(status, exception.code(), detail, request);
    }

    @ExceptionHandler(MerchantOwnerRejectedException.class)
    ResponseEntity<ProblemDetail> merchantOwner(
            MerchantOwnerRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "ACCOUNT_DISABLED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String detail = switch (exception.code()) {
            case "ACCOUNT_DISABLED" -> "该手机号对应的账户已被停用，无法开通商户";
            default -> "商户账户解析失败";
        };
        return response(status, exception.code(), detail, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不符合要求", request);
    }

    @ExceptionHandler(ProfileRejectedException.class)
    ResponseEntity<ProblemDetail> profile(
            ProfileRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "CONSUMER_NOT_FOUND", "AVATAR_UPLOAD_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AVATAR_UPLOAD_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "PROFILE_VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            case "CONTENT_REVIEW_UNAVAILABLE", "OBJECT_STORAGE_UNAVAILABLE",
                    "AVATAR_OBJECT_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String detail = switch (exception.code()) {
            case "NICKNAME_INVALID" -> "昵称必须为 2 至 20 个中文、字母、数字或下划线";
            case "PROFILE_CONTENT_REJECTED" -> "资料未通过内容安全审核";
            case "PROFILE_VERSION_CONFLICT" -> "资料已在其他位置更新，请刷新后重试";
            case "AVATAR_UPLOAD_EXPIRED" -> "头像上传凭证已过期";
            case "AVATAR_UPLOAD_FORBIDDEN", "AVATAR_UPLOAD_NOT_FOUND" -> "头像上传记录不可用";
            case "AVATAR_UPLOAD_INVALID", "AVATAR_OBJECT_MISMATCH" -> "头像文件类型、大小或完整性校验失败";
            case "CONTENT_REVIEW_UNAVAILABLE" -> "内容安全服务暂不可用";
            case "OBJECT_STORAGE_UNAVAILABLE", "AVATAR_OBJECT_UNAVAILABLE" -> "头像存储服务暂不可用";
            case "CONSUMER_NOT_FOUND" -> "未找到当前用户";
            default -> "资料更新失败";
        };
        return response(status, exception.code(), detail, request);
    }

    @ExceptionHandler(PaymentAuthorizationRejectedException.class)
    ResponseEntity<ProblemDetail> paymentAuthorization(
            PaymentAuthorizationRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "PAYMENT_PASSWORD_LOCKED" -> HttpStatus.LOCKED;
            case "PAYMENT_PASSWORD_NOT_SET", "PAYMENT_PASSWORD_DISABLED" ->
                    HttpStatus.FORBIDDEN;
            case "REAL_NAME_VERIFICATION_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "IDEMPOTENCY_KEY_REUSED", "PAYMENT_PASSWORD_ALREADY_SET" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String detail = switch (exception.code()) {
            case "PAYMENT_PASSWORD_INVALID" -> "支付密码错误";
            case "PAYMENT_PASSWORD_LOCKED" -> "支付密码错误次数过多，请十分钟后重试";
            case "PAYMENT_PASSWORD_NOT_SET" -> "请先完成支付密码初始化";
            case "PAYMENT_AUTHORIZATION_EXPIRED" -> "支付授权已过期，请重新验证";
            case "IDEMPOTENCY_KEY_REUSED" -> "同一幂等键不能用于不同的支付授权请求";
            default -> "支付授权无效、已过期或已使用";
        };
        return response(status, exception.code(), detail, request);
    }

    @ExceptionHandler(RealNameVerificationRejectedException.class)
    ResponseEntity<ProblemDetail> realName(
            RealNameVerificationRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "VERIFICATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "REAL_NAME_PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, exception.code(), "实名认证请求未通过", request);
    }

    @ExceptionHandler(FriendRequestRejectedException.class)
    ResponseEntity<ProblemDetail> friendRequest(
            FriendRequestRejectedException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "CANNOT_REQUEST_SELF" -> HttpStatus.BAD_REQUEST;
            case "ALREADY_FRIENDS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String detail = switch (exception.code()) {
            case "CANNOT_REQUEST_SELF" -> "不能向自己发送好友请求";
            case "ALREADY_FRIENDS" -> "已经是好友";
            default -> "好友请求处理失败";
        };
        return response(status, exception.code(), detail, request);
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request) {
        return response(status, code, detail, request, null);
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request,
            Long retryAfterSeconds) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty(
                "requestId",
                com.minipay.identity.infrastructure.security.RequestIdFilter.get(request));
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            response.header("Retry-After", Long.toString(retryAfterSeconds));
        }
        return response.body(problem);
    }
}
