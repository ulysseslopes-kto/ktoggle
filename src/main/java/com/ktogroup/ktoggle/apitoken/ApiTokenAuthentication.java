package com.ktogroup.ktoggle.apitoken;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * An authenticated API token. It carries the token's realm role, so the usual URL rules apply, plus the
 * {@value #MARKER} authority, which lets the domain tell automation apart from people without depending on this class.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    public static final String MARKER = "API_TOKEN";

    private final transient ApiToken token;

    public ApiTokenAuthentication(ApiToken token) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + token.role().realmRole()), new SimpleGrantedAuthority(MARKER)));
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return token.actor();
    }

    @Override
    public String getName() {
        return token.actor();
    }

    public ApiToken token() {
        return token;
    }
}
