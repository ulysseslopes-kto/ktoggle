# Local Keycloak (realm `mobilt`)

The realm is imported by `docker compose up` and is **local only**: these credentials are disposable and the
file is allowlisted in `.gitleaks.toml`.

| User | Password | Realm role |
|---|---|---|
| `admin.local` | `admin` | `ktoggle-admin` |
| `editor.local` | `editor` | `ktoggle-editor` |
| `viewer.local` | `viewer` | `ktoggle-viewer` |
| `approver.local` | `approver` | `ktoggle-approver` (aprova drafts; não edita) |

Get a token for the admin API (direct grant is enabled for the local `ktoggle-ui` client only):

```bash
TOKEN=$(curl -s -d client_id=ktoggle-ui -d grant_type=password -d username=admin.local -d password=admin \
  http://localhost:8180/realms/mobilt/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/admin/v1/features
```

In stg/prd the roles are created in the shared `mobilt` realm and assigned through the usual access process.
