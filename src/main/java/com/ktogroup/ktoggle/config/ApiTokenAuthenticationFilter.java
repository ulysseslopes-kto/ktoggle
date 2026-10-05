package com.ktogroup.ktoggle.config;

import com.ktogroup.ktoggle.apitoken.ApiToken;
import com.ktogroup.ktoggle.apitoken.ApiTokenAuthentication;
import com.ktogroup.ktoggle.apitoken.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer ktg_...} on the admin API. Keycloak JWTs are left to the resource server;
 * a {@code ktg_} token that is unknown, expired or revoked is rejected here with 401 and never reaches it.
 *
 * <p>Not a Spring bean on purpose: as a bean it would also be registered as a global servlet filter.
 */
class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String BEARER = "Bearer ";
    private static final String REJECTED = """
            {"message":"Invalid, expired or revoked API token","messageCode":"UNAUTHORIZED"}""";

    private final ApiTokenService service;

    ApiTokenAuthenticationFilter(ApiTokenService service) {
        this.service = service;
    }

    static boolean isApiToken(String authorization) {
        return authorization != null && authorization.startsWith(BEARER + ApiTokenService.SECRET_PREFIX);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/") || !isApiToken(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length()).strip();
        Optional<ApiToken> token = service.authenticate(secret);
        if (token.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(REJECTED.getBytes(StandardCharsets.UTF_8));
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiTokenAuthentication(token.get()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
