package com.ktogroup.ktoggle.commons.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ChangeContextProviderTest {

    private final ChangeContextProvider provider = new ChangeContextProvider();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void outside_a_request_the_context_belongs_to_the_system() {
        ChangeContext context = provider.current();

        assertThat(context.actor()).isEqualTo("system:unknown");
        assertThat(context.reason()).isNull();
        assertThat(context.changeId()).isNotNull();
    }

    @Test
    void outside_a_request_the_actor_is_the_authenticated_name_when_there_is_one() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("demo-seed", null, List.of()));

        ChangeContext context = provider.current();

        assertThat(context.actor()).isEqualTo("demo-seed");
        assertThat(context.reason()).isNull();
    }

    @Test
    void system_contexts_name_the_job_and_get_a_fresh_change_id_each_time() {
        ChangeContext first = ChangeContextProvider.system("reconciler");
        ChangeContext second = ChangeContextProvider.system("reconciler");

        assertThat(first.actor()).isEqualTo("system:reconciler");
        assertThat(first.changeId()).isNotEqualTo(second.changeId());
    }

    @Test
    void change_id_is_stable_within_a_request() {
        bindRequest(null);

        assertThat(provider.current().changeId()).isEqualTo(provider.current().changeId());
    }

    @Test
    void change_id_differs_between_requests() {
        bindRequest(null);
        ChangeContext first = provider.current();
        bindRequest(null);

        assertThat(provider.current().changeId()).isNotEqualTo(first.changeId());
    }

    @Test
    void reason_header_is_stripped() {
        bindRequest("  planned maintenance \t");

        assertThat(provider.current().reason()).isEqualTo("planned maintenance");
    }

    @Test
    void blank_reason_header_means_no_reason() {
        bindRequest("   ");

        assertThat(provider.current().reason()).isNull();
    }

    @Test
    void missing_reason_header_means_no_reason() {
        bindRequest(null);

        assertThat(provider.current().reason()).isNull();
    }

    @Test
    void overlong_reason_is_truncated_to_one_thousand_characters() {
        bindRequest("x".repeat(1500));

        assertThat(provider.current().reason()).hasSize(1000);
    }

    @Test
    void actor_is_the_preferred_username_of_the_jwt() {
        bindRequest(null);
        authenticate(jwtBuilder().claim("preferred_username", "alice").subject("kc-id-1").build());

        assertThat(provider.current().actor()).isEqualTo("alice");
    }

    @Test
    void actor_falls_back_to_the_jwt_subject_without_preferred_username() {
        bindRequest(null);
        authenticate(jwtBuilder().subject("kc-id-1").build());

        assertThat(provider.current().actor()).isEqualTo("kc-id-1");
    }

    @Test
    void actor_of_a_non_jwt_authentication_is_its_name() {
        bindRequest(null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("batch-user", "n/a", List.of()));

        assertThat(provider.current().actor()).isEqualTo("batch-user");
    }

    @Test
    void unauthenticated_requests_are_anonymous() {
        bindRequest(null);

        assertThat(provider.current().actor()).isEqualTo("anonymous");
    }

    private void bindRequest(String reasonHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (reasonHeader != null) {
            request.addHeader(ChangeContextProvider.REASON_HEADER, reasonHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("token").header("alg", "none").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
