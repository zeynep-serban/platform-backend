# Spike: Keycloak Token Exchange — User Impersonation v1

> **Date**: 2026-05-09
> **Realm**: `platform-test`
> **Keycloak version**: 26.x
> **Features enabled (kc.features)**: `token-exchange,admin-fine-grained-authz`
> **Codex thread**: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
> **Linked spec**: [docs/plans/2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md)

## Spike-1 sonucu (provisioning + first exchange attempt)

### ✅ Feature gate
```
kc.features = token-exchange, admin-fine-grained-authz (ENV)
```
Hem token-exchange hem fine-grained authz feature'ı runtime'da aktif. Gap yok.

### ✅ Broker client provisioning
| Attribute | Value |
|---|---|
| Client ID | `impersonation-broker` |
| Client UUID | `<redacted>` |
| Client Authenticator | `client-secret` |
| Service Accounts Enabled | `true` |
| Standard/Direct/Implicit Flow | `false` |
| Public Client | `false` |
| `attributes."token.exchange.permission.enabled"` | `true` |
| Service Account User UUID | `<redacted>` |

### ✅ Service account effective roles (`realm-management` client roles)
- `impersonation`
- `view-users`
- `query-users`

### ✅ Admin JWT (impersonator)
```
Username: d35-admin-persona
Email: d35-admin@example.com
Client: frontend (public)
Grant: password
Token length: 1431 chars
```

### ⚠ Token exchange request — config-gap (Spike-1 stop point)

**Request**:
```http
POST /realms/platform-test/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
client_id=impersonation-broker
client_secret=<redacted>
subject_token=<admin_jwt>
requested_subject=<canaryscope_target_uuid>
audience=frontend
```

**Response**:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"error":"access_denied","error_description":"Client not allowed to exchange"}
```

### Yorum

Endpoint, payload format, authentication (broker secret + admin JWT) tüm katmanlar geçti. **Authorization layer (Keycloak fine-grained authz policy)** default-deny enforcing — `frontend` client için exchange izin verilmesi gereken policy henüz oluşturulmadı.

Bu Keycloak 26.x'in beklenen davranışı: `attributes."token.exchange.permission.enabled"=true` set etmek client-side intent flag; fine-grained authz tarafında **explicit policy** gerek (hangi exchanger client'lar bu broker'dan token alabilir, hangi audience'lara token üretilebilir).

## Decision

**Spike-1 verdict**: `STRUCTURALLY_OK_POLICY_MISSING`

Spike-1 amacı (feature/client/auth basamaklarının çalışması) tam karşılandı. Kalan tek engel **fine-grained authz policy config** — bu zaten Codex iter-2'de PR-A scope'una alınmıştı; iter-3'te PR-A'nın **birinci acceptance gate'i** olarak ana scope haline geldi.

## Spike-2 (planned, post-PR-A)

PR-A apply edildikten sonra ikinci spike turu (operator manuel, ~1 saat):

### Acceptance kriteri (Codex iter-3 dictation)

```text
token_exchange_status=200|4xx
binding_model=native_act|jti_session_lookup|custom_claim|none
audience_backend=pass|fail
authz_me_with_exchanged_token=200|401|403
report_smoke_with_exchanged_token=200|401|403
decision=PR_B_READY|REVISE_REQUIRED
```

### Decoded claim acceptance

| Outcome | Decision |
|---|---|
| `act` claim native + `act.sub == admin_user_id` | ✅ **PASS_NATIVE_ACT** → PR-B `act` claim middleware path |
| `act` yok ama `jti` mevcut + broker session lookup mümkün | ✅ **PASS_JTI_SESSION_LOOKUP** → PR-B middleware DB lookup path (custom JWT signing'den daha pragmatik — Codex iter-3 §3 absorb) |
| Custom mapper ile deterministic `impersonator_*` claim | ✅ **PASS_CUSTOM_CLAIM** → PR-B middleware custom claim path |
| Hiçbir actor binding güvenilir değil | ❌ **FAIL_NO_BINDING** → design revise (PR-B ertelenir) |

### Spike-2 runbook (PR-A apply sonrası)

```bash
#!/bin/bash
set +x
# Pre: PR-A applied (broker client + policy live)
# Pre: ADMIN_JWT, TARGET_ID, BROKER_SECRET in env

EXCHANGE_RESP=$(curl -sk -X POST \
  "https://testai.acik.com/realms/platform-test/protocol/openid-connect/token" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  -d "client_id=impersonation-broker" \
  -d "client_secret=$BROKER_SECRET" \
  -d "subject_token=$ADMIN_JWT" \
  -d "requested_subject=$TARGET_ID" \
  -d "audience=frontend")

EXCHANGED=$(echo "$EXCHANGE_RESP" | jq -r '.access_token // empty')

if [ -z "$EXCHANGED" ]; then
  echo "FAIL: $EXCHANGE_RESP"
  exit 1
fi

# Decode claims
echo "$EXCHANGED" | cut -d. -f2 | python3 -c '
import base64, json, sys
p = sys.stdin.read().strip()
p += "=" * (-len(p) % 4)
c = json.loads(base64.urlsafe_b64decode(p))
keys = ["iss","sub","aud","azp","scope","typ","acr","exp","iat","jti",
        "email","preferred_username","act","impersonator_user_id",
        "impersonation_session_id","target_user_id"]
for k in keys:
    if k in c:
        print(f"{k}: {json.dumps(c[k], ensure_ascii=False)[:200]}")
'

# Backend smoke
echo ""
echo "=== authz/me with exchanged token ==="
curl -sk -o /dev/null -w "HTTP=%{http_code}\n" \
  -H "Authorization: Bearer $EXCHANGED" \
  "https://testai.acik.com/api/v1/authz/me"

echo "=== report endpoint smoke ==="
curl -sk -o /dev/null -w "HTTP=%{http_code}\n" \
  -H "Authorization: Bearer $EXCHANGED" \
  -H "X-Company-Id: 35" \
  "https://testai.acik.com/api/v1/reports/fin-muhasebe-detay/data?page=1&pageSize=5"
```

## Security notes (Codex iter-3 §absorb)

- ❌ **Secrets commit'lenmedi**: `/tmp/broker-secret.txt` operator host filesystem; Vault/ExternalSecret reference PR-A scope'unda
- ❌ **Token sample redacted**: bu doc'ta hiçbir gerçek JWT/secret/UUID yok
- ❌ **Denylist Keycloak policy'sinde değil**: Keycloak policy coarse gate (broker → frontend exchange OK), fine-grained "kimi impersonate edebilir" backend broker endpoint'inde fail-closed kapanır

## References

- Spec: [docs/plans/2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md)
- Codex thread: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
  - iter-2 ready_for_spike: true
  - iter-3 PARTIAL → ready_for_pr_a: true
- RFC 8693 OAuth 2.0 Token Exchange
- Keycloak 26 Server Administration: Token Exchange + fine-grained authz
