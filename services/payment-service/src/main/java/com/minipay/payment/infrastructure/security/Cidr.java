package com.minipay.payment.infrastructure.security;

import java.net.InetAddress;
import java.util.List;

/** Minimal IPv4/CIDR matcher for merchant-application IP allow lists. */
public final class Cidr {
    private Cidr() {}

    public static boolean matches(String ip, List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
            return false;
        }
        try {
            int address = toInt(InetAddress.getByName(ip.trim()));
            for (String entry : cidrs) {
                String cidr = entry == null ? "" : entry.trim();
                if (cidr.isEmpty()) {
                    continue;
                }
                String[] parts = cidr.split("/");
                int network = toInt(InetAddress.getByName(parts[0].trim()));
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 32;
                int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
                if ((address & mask) == (network & mask)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int toInt(InetAddress address) {
        byte[] bytes = address.getAddress();
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }
}
