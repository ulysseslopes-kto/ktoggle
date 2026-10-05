package com.ktogroup.ktoggle.ztest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Thin test client for the admin API, authenticated as a Keycloak user with the given realm role. */
public final class AdminApi {

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;
    private final String username;
    private final String role;

    public AdminApi(MockMvc mvc, ObjectMapper objectMapper, String username, String role) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.username = username;
        this.role = role;
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public JwtRequestPostProcessor auth() {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public JsonNode getJson(String path) throws Exception {
        return read(perform(get(path), 200));
    }

    public JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        return read(perform(post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
                expectedStatus));
    }

    public JsonNode postJson(String path, Object body, String reason, int expectedStatus) throws Exception {
        return read(perform(post(path).header("X-Ktoggle-Reason", reason).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)), expectedStatus));
    }

    public JsonNode putJson(String path, Object body) throws Exception {
        return read(perform(put(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), 200));
    }

    public JsonNode putJson(String path, Object body, int expectedStatus) throws Exception {
        return read(perform(put(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
                expectedStatus));
    }

    public JsonNode delete(String path, int expectedStatus) throws Exception {
        return read(perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path), expectedStatus));
    }

    public JsonNode getJson(String path, int expectedStatus) throws Exception {
        return read(perform(get(path), expectedStatus));
    }

    public MvcResult perform(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(request.with(auth())).andReturn();
        if (result.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("Expected HTTP %d but got %d: %s".formatted(expectedStatus,
                    result.getResponse().getStatus(), result.getResponse().getContentAsString()));
        }
        return result;
    }

    public JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }
}
