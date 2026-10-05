package com.ktogroup.ktoggle.savedgroup.zin;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.savedgroup.SavedGroup;
import com.ktogroup.ktoggle.savedgroup.SavedGroupService;
import com.ktogroup.ktoggle.savedgroup.zdto.SavedGroupRequest;
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

@Tag(name = "Saved groups")
@RestController
@RequestMapping("/admin/v1/saved-groups")
@RequiredArgsConstructor
public class SavedGroupController {

    private final SavedGroupService savedGroupService;

    @GetMapping
    public List<SavedGroup> list() {
        return savedGroupService.findAll();
    }

    @GetMapping("/{key}")
    public SavedGroup get(@PathVariable String key) {
        return savedGroupService.get(key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedGroup create(@Valid @RequestBody SavedGroupRequest request) {
        return savedGroupService.create(request.toCommand(request.key()));
    }

    @PutMapping("/{key}")
    public SavedGroup update(@PathVariable String key, @Valid @RequestBody SavedGroupRequest request) {
        if (request.version() == null) {
            throw ValidationException.of("version is required on update");
        }
        return savedGroupService.update(key, request.toCommand(key), request.version());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String key) {
        savedGroupService.delete(key);
    }
}
