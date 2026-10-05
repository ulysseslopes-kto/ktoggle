package com.ktogroup.ktoggle.webhook.zin;

import com.ktogroup.ktoggle.webhook.Webhook;
import com.ktogroup.ktoggle.webhook.WebhookDelivery;
import com.ktogroup.ktoggle.webhook.WebhookEvent;
import com.ktogroup.ktoggle.webhook.WebhookService;
import com.ktogroup.ktoggle.webhook.WebhookService.CreatedWebhook;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only. Deliveries are signed: see {@code WebhookSignature} for how receivers verify them. */
@RestController
@RequestMapping("/admin/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService service;

    @GetMapping
    public List<Webhook> list() {
        return service.findAll();
    }

    @Operation(summary = "Events a webhook can subscribe to")
    @GetMapping("/events")
    public List<EventView> events() {
        return Arrays.stream(WebhookEvent.values()).filter(e -> e != WebhookEvent.TEST)
                .map(e -> new EventView(e.code(), e.label())).toList();
    }

    @Operation(summary = "Create a webhook; the signing secret is in this response only")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedWebhook create(@Valid @RequestBody WebhookRequest request) {
        return service.create(request.name(), request.url(), request.format(), request.events());
    }

    @PutMapping("/{id}")
    public Webhook update(@PathVariable UUID id, @Valid @RequestBody WebhookRequest request) {
        return service.update(id, request.name(), request.url(), request.format(), request.events(),
                request.enabled() == null || request.enabled(), request.version() == null ? -1 : request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @Operation(summary = "Queue a test notification to this webhook")
    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void test(@PathVariable UUID id) {
        service.sendTest(id);
    }

    @GetMapping("/{id}/deliveries")
    public List<WebhookDelivery> deliveries(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        return service.deliveries(id, limit);
    }

    public record WebhookRequest(String name, String url, Webhook.Format format, @NotNull List<WebhookEvent> events,
                                 Boolean enabled, Long version) {
    }

    public record EventView(String code, String label) {
    }
}
