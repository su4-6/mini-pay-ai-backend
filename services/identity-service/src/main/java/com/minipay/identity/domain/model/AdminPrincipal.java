package com.minipay.identity.domain.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AdminPrincipal implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String displayName;
    private final String passwordHash;
    private final List<GrantedAuthority> authorities;

    public AdminPrincipal(UUID userId, String displayName, String passwordHash) {
        this(userId, displayName, passwordHash, List.of("platform_admin"));
    }

    public AdminPrincipal(UUID userId, String displayName, String passwordHash, List<String> roles) {
        this.userId = userId;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.authorities = roles.stream()
                .map(String::toUpperCase)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    public UUID userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return userId.toString();
    }
}
