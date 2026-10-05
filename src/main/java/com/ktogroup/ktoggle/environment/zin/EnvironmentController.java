package com.ktogroup.ktoggle.environment.zin;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.environment.Environment;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.environment.zdto.EnvironmentRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Environments")
@RestController
@RequestMapping("/admin/v1/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @GetMapping
    public List<Environment> list() {
        return environmentService.findAll();
    }

    @GetMapping("/{key}")
    public Environment get(@PathVariable String key) {
        return environmentService.get(key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Environment create(@Valid @RequestBody EnvironmentRequest request) {
        return environmentService.create(request.key(), request.name(), request.description(), request.sortOrder(),
                request.requiresReview());
    }

    @PutMapping("/{key}")
    public Environment update(@PathVariable String key, @Valid @RequestBody EnvironmentRequest request) {
        if (request.version() == null) {
            throw ValidationException.of("version is required on update");
        }
        return environmentService.update(key, request.name(), request.description(), request.sortOrder(),
                request.requiresReview(), request.version());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String key) {
        environmentService.delete(key);
    }
}
