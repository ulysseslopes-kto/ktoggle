package com.ktogroup.ktoggle.apitoken;

import com.ktogroup.ktoggle.audit.AuditAction;
import com.ktogroup.ktoggle.audit.AuditService;
import com.ktogroup.ktoggle.audit.EntityType;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import com.ktogroup.ktoggle.commons.change.ChangeContext;
import com.ktogroup.ktoggle.commons.change.ChangeContextProvider;
import com.ktogroup.ktoggle.commons.exception.ConflictException;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.commons.time.Ids;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, authenticates and revokes API tokens. Creation and revocation are audited; every change made with a token
 * is attributed to {@code token:<name>} by the regular audit trail.
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    public static final String SECRET_PREFIX = "ktg_";
    static final int PREFIX_LENGTH = SECRET_PREFIX.length() + 8;
    static final Duration MAX_LIFETIME = Duration.ofDays(366);
    /** "Last used" is written at most once per interval per token, so authentication does not write on every call. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,59}$");
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SECRET_LENGTH = 40;
    private static final String ENTITY = "API token";

    private final SecureRandom random = new SecureRandom();
    private final ApiTokenPersistencePort persistence;
    private final AuditService auditService;
    private final ChangeContextProvider changeContextProvider;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ApiToken> findAll() {
        return persistence.findAll();
    }

    /** Returns the token and its secret; the secret cannot be retrieved again. */
    @Transactional
    public CreatedToken create(String name, ApiTokenRole role, Instant expiresAt) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ValidationException.of("name must be 2-60 lowercase letters, digits, dots, underscores or hyphens");
        }
        if (role == null) {
            throw ValidationException.of("role is required (VIEWER or EDITOR)");
        }
        Instant now = Ids.now(clock);
        if (expiresAt != null && (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME)))) {
            throw ValidationException.of("expiresAt must be in the future and at most one year away");
        }
        if (persistence.existsActiveByName(name)) {
            throw ConflictException.alreadyExists(ENTITY, name);
        }
        String secret = SECRET_PREFIX + randomString(SECRET_LENGTH);
        ChangeContext context = changeContextProvider.current();
        ApiToken token = new ApiToken(Ids.newId(), name, secret.substring(0, PREFIX_LENGTH), Hashes.sha256Hex(secret), role,
                context.actor(), now, expiresAt, null, null, null);
        persistence.insert(token);
        auditService.record(context, AuditAction.CREATE, EntityType.API_TOKEN, name, null, token);
        return new CreatedToken(token, secret);
    }

    @Transactional
    public ApiToken revoke(UUID id) {
        ApiToken token = persistence.findById(id).orElseThrow(() -> new NotFoundException(ENTITY, id.toString()));
        if (token.revokedAt() != null) {
            return token;
        }
        ChangeContext context = changeContextProvider.current();
        Instant now = Ids.now(clock);
        persistence.revoke(id, context.actor(), now);
        ApiToken revoked = token.withRevokedBy(context.actor()).withRevokedAt(now);
        auditService.record(context, AuditAction.REVOKE, EntityType.API_TOKEN, token.name(), token, revoked);
        return revoked;
    }

    /** The active token for this secret, if any; records when it was last used. */
    @Transactional
    public Optional<ApiToken> authenticate(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX)) {
            return Optional.empty();
        }
        Instant now = Ids.now(clock);
        Optional<ApiToken> token = persistence.findByHash(Hashes.sha256Hex(secret)).filter(t -> t.activeAt(now));
        token.filter(t -> t.lastUsedAt() == null || t.lastUsedAt().isBefore(now.minus(TOUCH_INTERVAL)))
                .ifPresent(t -> persistence.touch(t.id(), now));
        return token;
    }

    private String randomString(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    /** {@code secret} is returned only in the creation response. */
    public record CreatedToken(ApiToken token, String secret) {
    }
}
