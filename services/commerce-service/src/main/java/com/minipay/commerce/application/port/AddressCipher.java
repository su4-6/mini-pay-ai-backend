package com.minipay.commerce.application.port;

public interface AddressCipher {
    EncryptedValue encrypt(String plaintext);

    record EncryptedValue(byte[] ciphertext, int keyVersion) {
        public EncryptedValue {
            ciphertext = ciphertext.clone();
        }
    }
}
