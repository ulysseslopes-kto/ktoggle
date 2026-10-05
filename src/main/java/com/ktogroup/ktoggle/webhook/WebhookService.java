package com.ktogroup.ktoggle.webhook;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of webhooks. Changes are audited; the secret never appears in the audit trail or in listings. */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String ENTITY = "Webhook";
    private static final int MAX_DELIVERIES = 100;

    private final SecureRandom random = new SecureRandom();
    private final WebhookPersistencePort persistence;
    private final WebhookNotifier notifier;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Webhook> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public Webhook get(UUID id) {
        return persistence.findById(id).orElseThrow(() -> new NotFoundException(ENTITY, id.toString()));
    }

    /** Returns the webhook and its signing secret, which is not shown again. */
    @Transactional
    public CreatedWebhook create(String name, String url, Webhook.Format format, List<WebhookEvent> events) {
        ChangeContext context = changeContextProvider.current();
        Instant now = Ids.now(clock);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String secret = "whsec_" + HexFormat.of().formatHex(bytes);
        Webhook webhook = new Webhook(Ids.newId(), requireName(name), requireUrl(url), format == null ? Webhook.Format.GENERIC : format,
                requireEvents(events), secret, true, context.actor(), now, context.actor(), now, 0);
        persistence.insert(webhook);
        auditService.record(context, AuditAction.CREATE, EntityType.WEBHOOK, webhook.name(), null, auditView(webhook));
        return new CreatedWebhook(webhook, secret);
    }

    @Transactional
    public Webhook update(UUID id, String name, String url, Webhook.Format format, List<WebhookEvent> events, boolean enabled,
                         long expectedVersion) {
        Webhook current = get(id);
        ChangeContext context = changeContextProvider.current();
        Webhook next = current.withName(requireName(name)).withUrl(requireUrl(url))
                .withFormat(format == null ? current.format() : format).withEvents(requireEvents(events)).withEnabled(enabled)
                .withUpdatedBy(context.actor()).withUpdatedAt(Ids.now(clock)).withVersion(expectedVersion + 1);
        if (!persistence.update(next, expectedVersion)) {
            throw ConflictException.staleVersion(ENTITY, id.toString(), expectedVersion, current.version());
        }
        auditService.record(context, AuditAction.UPDATE, EntityType.WEBHOOK, next.name(), auditView(current), auditView(next));
        return next;
    }

    @Transactional
    public void delete(UUID id) {
        Webhook current = get(id);
        persistence.delete(id);
        auditService.record(changeContextProvider.current(), AuditAction.DELETE, EntityType.WEBHOOK, current.name(),
                auditView(current), null);
    }

    @Transactional
    public void sendTest(UUID id) {
        notifier.test(get(id), changeContextProvider.current().actor());
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveries(UUID id, int limit) {
        get(id);
        return persistence.findDeliveries(id, Math.clamp(limit, 1, MAX_DELIVERIES));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw ValidationException.of("name is required (up to 100 characters)");
        }
        return name.strip();
    }

    private static String requireUrl(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.strip());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.toString().length() > 2000) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw ValidationException.of("url must be an absolute http(s) URL");
        }
    }

    private static List<WebhookEvent> requireEvents(List<WebhookEvent> events) {
        if (events == null || events.isEmpty()) {
            throw ValidationException.of("choose at least one event");
        }
        if (events.contains(WebhookEvent.TEST)) {
            throw ValidationException.of("the test event cannot be subscribed to; use Send test");
        }
        return List.copyOf(new LinkedHashSet<>(events));
    }

    private static Map<String, Object> auditView(Webhook w) {
        return Map.of("name", w.name(), "url", w.url(), "format", w.format().name(),
                "events", w.events().stream().map(WebhookEvent::code).toList(), "enabled", w.enabled());
    }

    public record CreatedWebhook(Webhook webhook, String secret) {
    }
}
