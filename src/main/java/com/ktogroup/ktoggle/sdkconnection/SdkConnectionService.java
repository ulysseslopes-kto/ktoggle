package com.ktogroup.ktoggle.sdkconnection;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.change.ConfigurationChangedEvent;
import com.ktogroup.ktoggle.commons.change.DeliverySettingsChangedEvent;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import com.ktogroup.ktoggle.environment.EnvironmentService;
import com.ktogroup.ktoggle.project.ProjectService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
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
    public Optional<SdkConnection> find(String clientKey) {
        return persistence.findByClientKey(clientKey);
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
        return update(clientKey, name, projectKeys, null, null, expectedVersion);
    }

    @Transactional
    public SdkConnection update(String clientKey, String name, List<String> projectKeys, Boolean encryptPayload,
                                long expectedVersion) {
        return update(clientKey, name, projectKeys, encryptPayload, null, expectedVersion);
    }

    /**
     * @param projectKeys    null keeps the current projects; an empty list means every project
     * @param encryptPayload null keeps the current setting; turning it on creates a key when there is none
     * @param remoteEval     null keeps the current setting
     */
    @Transactional
    public SdkConnection update(String clientKey, String name, List<String> projectKeys, Boolean encryptPayload,
                                Boolean remoteEval, long expectedVersion) {
        SdkConnection current = get(clientKey);
        if (current.version() != expectedVersion) {
            throw ConflictException.staleVersion(ENTITY, clientKey, expectedVersion, current.version());
        }
        boolean encrypt = encryptPayload == null ? current.encryptPayload() : encryptPayload;
        boolean remote = remoteEval == null ? current.remoteEval() : remoteEval;
        if (encrypt && remote) {
            throw ValidationException.of("Remote evaluation and payload encryption cannot be combined: "
                    + "with remote evaluation the rules never reach the SDK");
        }
        SdkConnection saved = persistence.save(current.withName(name)
                .withProjectKeys(projectKeys == null ? current.projectKeys() : validProjects(projectKeys))
                .withEncryptPayload(encrypt).withRemoteEval(remote)
                .withDecryptionKey(encrypt && current.decryptionKey() == null ? generateDecryptionKey() : current.decryptionKey())
                .withUpdatedAt(Ids.now(clock)));
        ChangeContext context = changeContextProvider.current();
        auditService.record(context, AuditAction.UPDATE, EntityType.SDK_CONNECTION, clientKey, current, saved);
        events.publishEvent(new ConfigurationChangedEvent(context));
        if (!saved.deliveryMode().equals(current.deliveryMode())) {
            events.publishEvent(new DeliverySettingsChangedEvent(clientKey));
        }
        return saved;
    }

    /**
     * Replaces the decryption key. SDKs holding the old key stop decrypting new payloads until they get the new one,
     * so rotate when the key leaked, and roll the new key out to the apps right away.
     */
    @Transactional
    public SdkConnection rotateDecryptionKey(String clientKey) {
        SdkConnection current = get(clientKey);
        SdkConnection saved = persistence.save(current.withDecryptionKey(generateDecryptionKey()).withUpdatedAt(Ids.now(clock)));
        auditService.record(changeContextProvider.current(), AuditAction.UPDATE, EntityType.SDK_CONNECTION, clientKey, current,
                saved);
        events.publishEvent(new DeliverySettingsChangedEvent(clientKey));
        return saved;
    }

    /**
     * Turns encryption on with a given key: used when importing a GrowthBook connection, so apps that already decrypt
     * with GrowthBook's key keep working when they switch host.
     */
    @Transactional
    public SdkConnection useDecryptionKey(String clientKey, String base64Key) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw ValidationException.of("decryption key must be base64");
        }
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw ValidationException.of("decryption key must be an AES key (16, 24 or 32 bytes)");
        }
        SdkConnection current = get(clientKey);
        if (current.remoteEval()) {
            throw ValidationException.of("Remote evaluation and payload encryption cannot be combined");
        }
        SdkConnection saved = persistence.save(current.withEncryptPayload(true).withDecryptionKey(base64Key).withUpdatedAt(Ids.now(clock)));
        auditService.record(changeContextProvider.current(), AuditAction.UPDATE, EntityType.SDK_CONNECTION, clientKey, current, saved);
        events.publishEvent(new DeliverySettingsChangedEvent(clientKey));
        return saved;
    }

    /** The key to configure in this connection's SDKs (admin only). */
    @Transactional(readOnly = true)
    public String decryptionKey(String clientKey) {
        SdkConnection connection = get(clientKey);
        if (connection.decryptionKey() == null) {
            throw new NotFoundException(MessageCode.ENTITY_NOT_FOUND, "Payload encryption is not enabled for " + clientKey);
        }
        return connection.decryptionKey();
    }

    /** Used by the bundle module for emergency rollback (pin) and its release (unpin). */
    @Transactional
    public SdkConnection setPinnedBundle(String clientKey, String bundleHash) {
        SdkConnection current = get(clientKey);
        return persistence.save(current.withPinnedBundleHash(bundleHash).withUpdatedAt(Ids.now(clock)));
    }

    private List<String> validProjects(List<String> projectKeys) {
        if (projectKeys == null) {
            return List.of();
        }
        projectKeys.forEach(projectService::get);
        return projectKeys.stream().distinct().sorted().toList();
    }

    /** 128-bit AES key, base64, as GrowthBook SDKs expect. */
    private static String generateDecryptionKey() {
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String generateClientKey() {
        StringBuilder key = new StringBuilder("sdk-");
        for (int i = 0; i < 16; i++) {
            key.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return key.toString();
    }
}
