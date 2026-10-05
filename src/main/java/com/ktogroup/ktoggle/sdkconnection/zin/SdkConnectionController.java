package com.ktogroup.ktoggle.sdkconnection.zin;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.sdkconnection.SdkConnection;
import com.ktogroup.ktoggle.sdkconnection.SdkConnectionService;
import com.ktogroup.ktoggle.sdkconnection.zdto.SdkConnectionRequest;
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

@Tag(name = "SDK connections")
@RestController
@RequestMapping("/admin/v1/sdk-connections")
@RequiredArgsConstructor
public class SdkConnectionController {

    private final SdkConnectionService sdkConnectionService;

    @GetMapping
    public List<SdkConnection> list() {
        return sdkConnectionService.findAll();
    }

    @GetMapping("/{clientKey}")
    public SdkConnection get(@PathVariable String clientKey) {
        return sdkConnectionService.get(clientKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SdkConnection create(@Valid @RequestBody SdkConnectionRequest request) {
        if (request.environmentKey() == null) {
            throw ValidationException.of("environmentKey is required");
        }
        return sdkConnectionService.create(request.clientKey(), request.name(), request.environmentKey(), request.projectKeys());
    }

    @PutMapping("/{clientKey}")
    public SdkConnection update(@PathVariable String clientKey, @Valid @RequestBody SdkConnectionRequest request) {
        if (request.version() == null) {
            throw ValidationException.of("version is required on update");
        }
        return sdkConnectionService.update(clientKey, request.name(), request.projectKeys(), request.version());
    }
}
