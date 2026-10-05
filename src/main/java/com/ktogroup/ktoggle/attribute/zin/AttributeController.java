package com.ktogroup.ktoggle.attribute.zin;

import com.ktogroup.ktoggle.attribute.Attribute;
import com.ktogroup.ktoggle.attribute.AttributeService;
import com.ktogroup.ktoggle.attribute.zdto.AttributeRequest;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attributes")
@RestController
@RequestMapping("/admin/v1/attributes")
@RequiredArgsConstructor
public class AttributeController {

    private final AttributeService attributeService;

    @GetMapping
    public List<Attribute> list() {
        return attributeService.findAll();
    }

    @GetMapping("/{key}")
    public Attribute get(@PathVariable String key) {
        return attributeService.get(key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Attribute create(@Valid @RequestBody AttributeRequest request) {
        return attributeService.create(request.toCommand(request.key()));
    }

    @PutMapping("/{key}")
    public Attribute update(@PathVariable String key, @Valid @RequestBody AttributeRequest request) {
        if (request.version() == null) {
            throw ValidationException.of("version is required on update");
        }
        return attributeService.update(key, request.toCommand(key), request.version());
    }
}
