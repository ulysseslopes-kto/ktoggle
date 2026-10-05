package com.ktogroup.ktoggle.config;

import com.ktogroup.ktoggle.apitoken.ApiTokenService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two audiences:
 * <ul>
 *   <li><b>SDKs</b> ({@code /api/**}, {@code /sub/**}): public, identified by the client key, CORS open —
 *   the same contract as the GrowthBook API/Proxy.</li>
 *   <li><b>Admin</b> ({@code /admin/**}): Keycloak JWT. Realm roles {@code ktoggle-viewer} &lt;
 *   {@code ktoggle-editor} &lt; {@code ktoggle-admin}.</li>
 *   <li><b>Automation</b> ({@code /admin/**}): API tokens ({@code Bearer ktg_...}) with the viewer or editor role.</li>
 * </ul>
 */
@Configuration
public class SecurityConfiguration {

    public static final String VIEWER = "ktoggle-viewer";
    public static final String EDITOR = "ktoggle-editor";
    public static final String ADMIN = "ktoggle-admin";
    /** May review drafts (when listed in the review settings); implies read access. */
    public static final String APPROVER = "ktoggle-approver";

    /** Admin-only resources: they change who can read flags or the trust chain itself. */
    private static final String[] ADMIN_ONLY = {
            "/admin/v1/environments/**",
            "/admin/v1/sdk-connections/**",
            "/admin/v1/attributes/**",
            "/admin/v1/projects/**",
            "/admin/v1/settings/**",
            "/admin/v1/api-tokens/**",
    };

    /** Admin-only even for reading: who holds which credential is not for every viewer. */
    private static final String[] ADMIN_ONLY_READ = {
            "/admin/v1/api-tokens/**",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiTokenService apiTokens) throws Exception {
        DefaultBearerTokenResolver jwtOnly = new DefaultBearerTokenResolver();
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**", "/sub/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers(ADMIN_ONLY_READ).hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/admin/**").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.POST, "/admin/v1/simulate", "/admin/v1/replay/**").hasRole(VIEWER)
                        // Reviewing is governed by the review settings (checked in DraftService), not by the editor role.
                        .requestMatchers(HttpMethod.POST, "/admin/v1/drafts/*/approve", "/admin/v1/drafts/*/request-changes",
                                "/admin/v1/drafts/*/comments").hasRole(VIEWER)
                        .requestMatchers(ADMIN_ONLY).hasRole(ADMIN)
                        .requestMatchers("/admin/**").hasRole(EDITOR)
                        .anyRequest().denyAll())
                .addFilterBefore(new ApiTokenAuthenticationFilter(apiTokens), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth -> oauth
                        // API tokens are handled by ApiTokenAuthenticationFilter; only Keycloak JWTs reach the resource server
                        .bearerTokenResolver(request -> ApiTokenAuthenticationFilter.isApiToken(
                                request.getHeader(HttpHeaders.AUTHORIZATION)) ? null : jwtOnly.resolve(request))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())));
        return http.build();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(ADMIN).implies(EDITOR)
                .role(EDITOR).implies(VIEWER)
                .role(APPROVER).implies(VIEWER)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${ktoggle.cors.admin-origins:}") List<String> adminOrigins) {
        CorsConfiguration sdk = new CorsConfiguration();
        sdk.addAllowedOriginPattern("*");
        sdk.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        sdk.addAllowedHeader("*");
        sdk.setExposedHeaders(List.of("ETag", "X-Ktoggle-Bundle", "x-sse-support"));

        CorsConfiguration admin = new CorsConfiguration();
        admin.setAllowedOrigins(adminOrigins);
        admin.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        admin.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", sdk);
        source.registerCorsConfiguration("/sub/**", sdk);
        source.registerCorsConfiguration("/admin/**", admin);
        return source;
    }

    /** Maps Keycloak {@code realm_access.roles} to {@code ROLE_*} authorities. */
    static JwtAuthenticationConverter keycloakRealmRoles() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::realmRoles);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
                .flatMap(role -> role instanceof String s ? Stream.of(s) : Stream.empty())
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
