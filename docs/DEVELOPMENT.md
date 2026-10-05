# Development guide

Architecture, conventions and invariants of the ktoggle backend.

## What this is

**ktoggle** is KTO's feature-flag service, built to replace GrowthBook. Two promises guide every change:

1. **Wire compatibility with the official GrowthBook SDKs.** `/api/features/{clientKey}` and `/sub/{clientKey}` (SSE)
   serve the GrowthBook payload format, so consumers (growthbook-sdk-java 0.10.x in the Java services,
   `@growthbook/growthbook` in mobile-bff, `growthbook-react` in mono-fe) migrate by changing only the API host.
2. **Every decision is auditable, independent of the current version.** Configuration is published as immutable,
   content-addressed, signed **bundles**. Any past decision can be replayed from its bundle.

**Current phase: standalone product for a demo.** ktoggle is not integrated or deployed anywhere. Do not touch other
KTO repositories or infrastructure (gitops, ECR, other services), and do not add migration/pilot features unless asked.

Scope of V1: flags + targeting (force and rollout rules, saved groups, environments, projects). **Experiment rules
are phase 2**, so GrowthBook cannot be switched off before that (mono-fe uses experiments).

## Build & run

```bash
./mvnw clean verify            # unit (*Test) + integration (*IT, embedded PostgreSQL via zonky, no Docker) + JaCoCo
./mvnw test -Dtest=GrowthBookSdkContractTest
docker compose up -d           # Postgres 16, Redis 7, Keycloak 26 (realm mobilt, see local-infra/keycloak)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # http://localhost:8090/swagger-ui.html
```

- Before the first commit exists, add `-Dmaven.gitcommitid.skip=true` (the git-commit-id plugin needs a HEAD).
- Coverage gate 80% instruction/branch: warns locally, blocks in CI (`ci` profile auto-activates with `CI=true`).
- Java 21 pinned in `.sdkmanrc`; Spring Boot 3.5; Jetty with virtual threads.

## Architecture

Package-by-feature with the house **zin / zout / zdto** convention (same as player-service):
domain records, services and `*PersistencePort` interfaces live in the feature package; `zin/` = REST controllers,
`zout/` = JPA/JDBC/Redis/AWS adapters, `zdto/` = request DTOs. `ArchitectureTest` enforces: zin never touches
zout, persistence/AWS types stay in zout (and config), domain has no web dependencies, **no package cycles**.

| Package | Responsibility |
|---|---|
| `project`, `environment`, `attribute`, `savedgroup`, `sdkconnection` | Catalog (CRUD + audit) |
| `feature` | Features, per-environment settings, sealed `Rule` (force / rollout), revisions |
| `targeting` | `ConditionValidator` (Mongo-like conditions supported by every SDK in use) |
| `bundle` | `PayloadCompiler` (domain → GrowthBook format), `BundleCodec` (JCS + SHA-256 + ECDSA), `BundlePublisher`, activation chain, rollback/pin, S3 WORM archive, KMS signer |
| `delivery` | `ActiveBundleRegistry` (in-memory, verified), SDK endpoints, SSE hub, delivery log |
| `evaluation` | simulate / replay using the **official growthbook-sdk-java** (no re-implemented evaluator) |
| `decision` | opt-in decision events from SDK callbacks, LGPD filtering, HMAC attribute digests, monthly partitions |
| `audit` | hash-chained control-plane audit log |
| `commons`, `config` | canonical JSON, hashing, change context, errors, security, scheduling |

### Invariants — do not break

- **Canonicalization and hashing are frozen** (`CanonicalJson`, RFC 8785). Golden values in `CanonicalJsonTest` must
  never be edited in place; any change needs a new contract version (`ktoggle.bundle.v2`).
- **Bundle hash covers content only** (`BundleBody`): never add timestamps/authors to the body — they go in the
  envelope (`Bundle`) or in the activation chain (`BundleActivation`).
- **Append-only tables** (`audit_log`, `feature_revision`, `bundle`, `bundle_activation`) have triggers rejecting
  UPDATE/DELETE. Never write migrations that update them; features are archived, never deleted.
- Every configuration mutation goes through its service so it produces: audit entry (same transaction), revision
  (features), and a `ConfigurationChangedEvent` → publication after commit. The reconciler repairs missed publications.
- **Drafts (GrowthBook-style):** existing features are never changed directly through the API. Every change (toggle,
  rules, default value, metadata, archive, revert) is staged in a `FeatureDraft`; `DraftService.publish` is the only
  path to `FeatureService.publish`. Environments with `requiresReview` need an approval from an eligible approver
  (`ReviewSettings`: roles/users, self-approval, reset-on-change, admin bypass with mandatory reason →
  `BYPASS_PUBLISH_DRAFT`). Drafts are merged three-way with the live feature (`DraftMerger`); conflicts block
  publication until a rebase. Feature creation stays direct (new features are disabled everywhere).
- Publication, rollback and unpin hold the `lockPublication()` advisory lock: chains never fork.
- A bundle is served only after `BundleCodec.verify` (canonical form, recomputed hash, trusted signature).
- **Saved groups are inlined** into rule conditions; `$inGroup` is rejected (growthbook-sdk-java 0.10.x lacks it).
- Features disabled in an environment are **omitted** from the payload (SDK evaluates them to null/off), like GrowthBook.
- Personal data: attributes are PII by default; decision events keep only non-PII attributes in clear plus an HMAC
  digest. Never log attribute values. The delivery log stores no IPs.

## Conventions

- Lombok `@RequiredArgsConstructor`/`@Slf4j`, constructor injection, records for DTOs/domain.
- Errors: throw `KtoggleException` subclasses with a `MessageCode`; `CoreExceptionHandler` renders `ErrorResponse`.
- Updates carry the `version` they were based on (optimistic locking → 409 `CONCURRENT_MODIFICATION`).
- Optional `X-Ktoggle-Reason` header on mutations (required for bundle rollback) is stored in audit and revisions.
- Roles (Keycloak realm `mobilt`): `ktoggle-viewer` < `ktoggle-editor` < `ktoggle-admin` (see `SecurityConfiguration`).
- Tests: `*Test` unit (no Spring), `*IT` with `@IntegrationTest` (shared context — avoid `@MockBean`/`@DirtiesContext`).
  Call scheduled methods directly (scheduling is disabled in the `test` profile).
- Commits: Conventional Commits with the Jira key, e.g. `feat(KIB-1234): ...`. Branches `dev`/`stg`/`prd`.
