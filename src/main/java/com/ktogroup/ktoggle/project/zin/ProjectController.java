package com.ktogroup.ktoggle.project.zin;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.project.Project;
import com.ktogroup.ktoggle.project.ProjectService;
import com.ktogroup.ktoggle.project.zdto.ProjectRequest;
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

@Tag(name = "Projects")
@RestController
@RequestMapping("/admin/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public List<Project> list() {
        return projectService.findAll();
    }

    @GetMapping("/{key}")
    public Project get(@PathVariable String key) {
        return projectService.get(key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project create(@Valid @RequestBody ProjectRequest request) {
        return projectService.create(request.key(), request.name(), request.description(), request.editorRoles(),
                request.editorUsers());
    }

    @PutMapping("/{key}")
    public Project update(@PathVariable String key, @Valid @RequestBody ProjectRequest request) {
        if (request.version() == null) {
            throw ValidationException.of("version is required on update");
        }
        return projectService.update(key, request.name(), request.description(), request.editorRoles(), request.editorUsers(),
                request.version());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String key) {
        projectService.delete(key);
    }
}
