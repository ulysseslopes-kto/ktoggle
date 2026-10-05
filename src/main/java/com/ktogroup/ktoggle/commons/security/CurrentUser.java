package com.ktogroup.ktoggle.commons.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The authenticated admin user: Keycloak username and realm roles (as granted in the token, without hierarchy). */
@Component
public class CurrentUser {

    private static final String ROLE_PREFIX = "ROLE_";

    public String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            return username != null ? username : jwt.getToken().getSubject();
        }
        return authentication != null ? authentication.getName() : "anonymous";
    }

    public Set<String> roles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasRole(String role) {
        return roles().contains(role);
    }
}
