package com.minipay.identity.application.service;

import com.minipay.identity.infrastructure.persistence.PaymentAuthorizationRepository;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPasswordService {
    private final PaymentAuthorizationRepository repository;
    private final PasswordEncoder encoder;

    public PaymentPasswordService(
            PaymentAuthorizationRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Transactional
    public void setInitial(UUID userId, String paymentPassword) {
        if (paymentPassword == null || !paymentPassword.matches("\\d{6}")) {
            throw new PaymentAuthorizationRejectedException("PAYMENT_PASSWORD_INVALID");
        }
        if (!repository.insertPaymentCredential(userId, encoder.encode(paymentPassword))) {
            throw new PaymentAuthorizationRejectedException("PAYMENT_PASSWORD_ALREADY_SET");
        }
    }
}
