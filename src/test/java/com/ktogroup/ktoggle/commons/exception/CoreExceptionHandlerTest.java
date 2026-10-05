package com.ktogroup.ktoggle.commons.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class CoreExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new CoreExceptionHandler()).build();

    @Test
    void business_exceptions_keep_their_status_and_message_code() throws Exception {
        mvc.perform(get("/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Feature 'x' not found"));
        mvc.perform(get("/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("ENTITY_IN_USE"));
        mvc.perform(get("/bad-request")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("INVALID_CONDITION"))
                .andExpect(jsonPath("$.data[0]").value("first problem"))
                .andExpect(jsonPath("$.data[1]").value("second problem"));
    }

    @Test
    void server_side_business_failures_are_5xx_with_their_code() throws Exception {
        mvc.perform(get("/integrity")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messageCode").value("BUNDLE_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.message").value("tampered"));
    }

    @Test
    void optimistic_locking_failures_become_a_concurrent_modification_conflict() throws Exception {
        mvc.perform(get("/optimistic")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void access_denied_is_forbidden() throws Exception {
        mvc.perform(get("/denied")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("nope"))
                .andExpect(jsonPath("$.messageCode").doesNotExist());
    }

    @Test
    void unexpected_errors_never_leak_their_details() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messageCode").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    @Test
    void invalid_request_bodies_list_every_failing_field() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(jsonPath("$.data[0]").value(org.hamcrest.Matchers.startsWith("name:")));
    }

    @Test
    void unreadable_request_bodies_are_a_validation_error() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/not-found")
        void notFound() {
            throw new NotFoundException("Feature", "x");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw ConflictException.inUse("Project", "p", "features");
        }

        @GetMapping("/bad-request")
        void badRequest() {
            throw new ValidationException(MessageCode.INVALID_CONDITION, "Invalid targeting condition",
                    List.of("first problem", "second problem"));
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new IntegrityException("tampered");
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internals");
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody Body body) {
        }
    }

    record Body(@NotBlank String name) {
    }
}
