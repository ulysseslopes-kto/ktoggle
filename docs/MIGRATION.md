# Migrating from GrowthBook

ktoggle can take over from an existing GrowthBook without changing any application code: the GrowthBook SDKs already in
use keep working, and each service switches by changing the API host (client keys are preserved). This guide covers the
tooling that makes the switch safe and the runbook to follow once the migration is approved.

Everything below is read-only towards GrowthBook. Nothing in GrowthBook or in the services changes until a cutover.

## What is in place

| Piece | What it does |
|---|---|
| **Importer** (`POST /admin/v1/growthbook/import`, page *Migration → From GrowthBook*) | Reads projects, environments, attributes, saved groups, features and SDK connections from GrowthBook's REST API and creates them in ktoggle. Dry run by default, with a report per item. Idempotent: running it again applies only what changed in GrowthBook. |
| **Exact semantics** | Rule ids, rollout seeds (GrowthBook hashes rollouts with the rule id) and experiment hash versions (v1 unless set) are kept, so after the switch every user keeps the same value and the same experiment variation. Client keys and encryption keys are kept, so apps do not need new credentials. |
| **Shadow comparison** (`POST /admin/v1/shadow/run`, scheduled when enabled) | For each client key, reads the payload GrowthBook serves and the one ktoggle serves, and evaluates both with the official SDK for the same simulated users (2,000 by default, built from the values the rules compare with). Any feature where a user would get a different value is reported with examples. |
| **Readiness** | A client key is *ready to migrate* after N clean comparisons in a row (3 by default). |
| **Metrics** | `ktoggle_shadow_runs_total{status}` and `ktoggle_shadow_divergent_features{client_key}`, for dashboards and alerts. |

What is **not** imported (reported as *unsupported* in the dry run): safe rollouts, namespaces, multi-arm bandits, visual
editor and URL redirect experiments, several "any" saved-group blocks in one rule, experiments that are not running, and
attribute types without an equivalent. Experiment analysis stays in the analytics tool (Mixpanel), as today.

## Try it locally (sample data, no KTO systems)

The repository includes a disposable, open source GrowthBook to exercise the importer and the shadow mode end to end.

```bash
docker compose --profile app --profile growthbook up -d     # ktoggle + a local GrowthBook (UI http://localhost:3300)
node local-infra/growthbook/seed.mjs                        # sample features, saved groups and an SDK connection
docker compose --profile app up -d ktoggle                  # restart ktoggle so it reads local-infra/growthbook/local.env
```

Then, as `admin.local`, open **Migration → From GrowthBook**: run a dry run, import, and click *Compare now* three times.
The GrowthBook connection reaches *ready to migrate*. Change a rule in the local GrowthBook (http://localhost:3300,
`admin@growthbook.local` / `growthbook-local-admin`) and compare again: the shadow mode reports the feature, how many
users differ and examples. Import again and it matches again.

## Taking it to KTO (after approval)

### 1. Prerequisites

- A **read-only** GrowthBook secret API key, stored in AWS Secrets Manager (`/secret/ktoggle`) as
  `ktoggle.growthbook.secret-key`. It is never logged nor returned by the API.
- Network access from ktoggle to the GrowthBook API (`ktoggle.growthbook.api-host`) and, if SDKs read from the GrowthBook
  Proxy, to the proxy (`ktoggle.growthbook.sdk-host`).
- The ktoggle environments that GrowthBook's environments map to (for example `production → prd`, `staging → stg`).

### 2. Import

1. Run a **dry run** with the environment mapping. Review every *unsupported* or *failed* item with the owning squad.
2. Run the **import**. Features are published directly and audited as `IMPORT` (no drafts: the import mirrors what is
   already live in GrowthBook).
3. Keep GrowthBook as the source of truth until each cutover: re-run the import after changes in GrowthBook (it only
   applies the differences). Editing the same features in both tools during this period is not supported.

### 3. Shadow mode

1. Enable the scheduled comparison: `ktoggle.growthbook.shadow.enabled=true` (`interval`, `samples` and `ready-after`
   are configurable).
2. Alert on `ktoggle_shadow_divergent_features > 0`. Every divergence links to examples in the console.
3. A client key is a cutover candidate once it is **ready to migrate**. Investigate any divergence before going on; the
   usual causes are a change made in GrowthBook after the last import (re-import) or an unsupported rule (decide with
   the squad).

### 4. Cutover, one service at a time

Start with a low-risk service; leave payment and bet placement flows for last.

1. Confirm the client key is *ready to migrate* and the latest import has no pending differences.
2. Freeze edits to that service's features in GrowthBook (announce it to the squad).
3. Change the service's GrowthBook API host to ktoggle in its deployment values (gitops). The client key does not change.
   With `SERVER_SENT_EVENTS` in the Java SDK the service also gets changes in about a second instead of on the cache TTL.
4. Watch the service's delivery log in ktoggle (*SDK connections → Deliveries*): the client key starts polling or
   streaming from ktoggle. Watch the service's own error rates and business metrics.
5. From now on, edit that service's features in ktoggle only (drafts, reviews and audit apply).

**Rollback:** revert the deployment values to GrowthBook's host. Nothing else is needed: GrowthBook still holds the
configuration, and ktoggle keeps its own history.

### 5. Decommission

When every client key has been switched and GrowthBook has received no SDK traffic for an agreed period, archive
GrowthBook's data (export) and end the contract.
