package com.minipay.identity.application.port;

public interface RealNameVerificationPort {
    VerificationResult verify(String legalName, String idNumber, byte[] faceJpeg);

    record VerificationResult(boolean verified, String providerReference, String failureCode) {
    }
}
