package com.minipay.commerce.application;

import com.minipay.commerce.application.port.AddressCipher;
import com.minipay.commerce.application.port.CommerceRepository;
import com.minipay.commerce.domain.model.DeliveryAddress;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceAddressService {
    private final CommerceRepository repository;
    private final AddressCipher cipher;
    private final Clock clock = Clock.systemUTC();

    public CommerceAddressService(CommerceRepository repository, AddressCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<DeliveryAddress> list(UUID userId) {
        return repository.listAddresses(userId, 20);
    }

    @Transactional
    public DeliveryAddress create(
            UUID userId,
            String label,
            String recipient,
            String mobile,
            String address,
            String zoneCode,
            boolean defaultAddress) {
        AddressCipher.EncryptedValue recipientValue = cipher.encrypt(recipient);
        AddressCipher.EncryptedValue mobileValue = cipher.encrypt(mobile);
        AddressCipher.EncryptedValue addressValue = cipher.encrypt(address);
        if (recipientValue.keyVersion() != mobileValue.keyVersion()
                || recipientValue.keyVersion() != addressValue.keyVersion()) {
            throw new IllegalStateException("Address encryption key version changed mid-request");
        }
        String summary = label.strip() + " · " + zoneCode.strip() + " · ***";
        return repository.createAddress(UuidV7.generate(), userId, label.strip(), summary,
                recipientValue.ciphertext(), mobileValue.ciphertext(), addressValue.ciphertext(),
                recipientValue.keyVersion(), zoneCode.strip(), defaultAddress, clock.instant());
    }
}
