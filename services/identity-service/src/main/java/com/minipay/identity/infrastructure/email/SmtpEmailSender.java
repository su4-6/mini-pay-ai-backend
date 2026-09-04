package com.minipay.identity.infrastructure.email;

import com.minipay.identity.application.port.EmailSender;
import com.minipay.identity.application.service.AccountSecurityRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minipay.identity.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mailSender;
    private final String from;
    public SmtpEmailSender(JavaMailSender mailSender, @org.springframework.beans.factory.annotation.Value("${minipay.identity.email.from}") String from) {
        this.mailSender = mailSender; this.from = from;
    }
    @Override public void sendVerificationCode(String email, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from); message.setTo(email); message.setSubject("MiniPay 邮箱验证码");
            message.setText("验证码：" + code + "，5 分钟内有效。请勿向他人泄露。");
            mailSender.send(message);
        } catch (RuntimeException exception) { throw new AccountSecurityRejectedException("EMAIL_DELIVERY_UNAVAILABLE"); }
    }
}
