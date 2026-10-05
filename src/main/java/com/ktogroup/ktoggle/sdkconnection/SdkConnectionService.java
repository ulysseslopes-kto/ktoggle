package com.ktogroup.ktoggle.sdkconnection;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.project.ProjectService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SDK connections are never deleted and never change environment: a client key must always mean the same
 * thing, otherwise the bundle history attached to it would be ambiguous.
 */
@Service
@RequiredArgsConstructor
public class SdkConnectionService {

    private static final String ENTITY = "SDK connection";
    private static final Pattern CLIENT_KEY = Pattern.compile("^sdk-[A-Za-z0-9]{8,64}$");
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SdkConnectionPersistencePort persistence;
    private final EnvironmentService environmentService;
    private final ProjectService projectService;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SdkConnection> findAll() {
        return persistence.findAll();
    }

    @Transactional(readOnly = true)
    public SdkConnection get(String clientKey) {
        return persistence.findByClientKey(clientKey).orElseThrow(() -> new NotFoundException(ENTITY, clientKey));
    }

    /**
     * @param clientKey optional: imported connections keep their GrowthBook key so consumers only change the host
     */
    @Transactional
    public SdkConnection create(String clientKey, String name, String environmentKey, List<String> projectKeys) {
        String key = clientKey == null ? generateClientKey() : clientKey;
        if (!CLIENT_KEY.matcher(key).matches()) {
            throw ValidationException.of("clientKey must match " + CLIENT_KEY.pattern());
        }
        if (persistence.existsByClientKey(key)) {
            throw ConflictException.alreadyExists(ENTITY, key);
        }
        environmentService.requireExists(environmentKey);
        List<String> projects = validProjects(projectKeys);
        Instant now = Ids.now(clock);
        SdkConnection saved = persistence.save(new SdkConnection(key, name, environmentKey, projects, null, now, now, null));
        ChangeContext context = changeContextProvider.current();
        auditService.record(context, AuditAction.CREATE, EntityType.SDK_CONNECTION, key, null, saved);
        events.publishEvent(new ConfigurationChangedEvent(context));
        return saved;
    }

    @Transactional
    public SdkConnection update(String clientKey, String name, List<String> projectKeys, long expectedVersion) {
        SdkConnection current = get(clientKey);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, clientKey, expectedVersion, current.version());
        }
        SdkConnection saved = persistence.save(new SdkConnection(clientKey, name, current.environmentKey(),
                validProjects(projectKeys), current.pinnedBundleHash(), current.createdAt(), Ids.now(clock), current.version()));
        ChangeContext context = changeContextProvider.current();
        auditService.record(context, AuditAction.UPDATE, EntityType.SDK_CONNECTION, clientKey, current, saved);
        events.publishEvent(new ConfigurationChangedEvent(context));
        return saved;
    }

    /** Used by the bundle module for emergency rollback (pin) and its release (unpin). */
    @Transactional
    public SdkConnection setPinnedBundle(String clientKey, String bundleHash) {
        SdkConnection current = get(clientKey);
        return persistence.save(new SdkConnection(clientKey, current.name(), current.environmentKey(),
                current.projectKeys(), bundleHash, current.createdAt(), Ids.now(clock), current.version()));
    }

    private List<String> validProjects(List<String> projectKeys) {
        if (projectKeys == null) {
            return List.of();
        }
        projectKeys.forEach(projectService::get);
        return projectKeys.stream().distinct().sorted().toList();
    }

    private static String generateClientKey() {
        StringBuilder key = new StringBuilder("sdk-");
        for (int i = 0; i < 16; i++) {
            key.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return key.toString();
    }
}
