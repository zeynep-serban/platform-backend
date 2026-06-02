# Audio Gateway Contract v1.0

> **Status**: FROZEN (2026-06-02). 3-AI mutabakat sonrası canonical (PR-gw-01 v2).
>
> Değişiklik = breaking change → yeni major version (v2) + ADR + Codex consensus.

## Mutabakat Trail

- Codex `019e879c-c51e-7691-8f16-69c781fb787e` AGREE final (Mavis 5 revize absorb)
- Codex `019e8846-2d7a-7133-8488-ddeb8ddc04cc` iter-1 REVISE absorb (ErrorResponse body + mockJwt + companyId/userId claim)
- Codex `019e887c-1866-7560-b51c-55cc81989a62` v2 clean restart AGREE
- Mavis `mvs_c922505d66a94a45b031feb3489f9488` msg `74` PARTIAL → msg `78` AGREE
- Claude (implementer) AGREE

## Pozisyon

`audio-gateway-service` tek client-facing ingress. Mobile/Web ↔ `platform-ai` doğrudan bağlantı **YASAK**.

---

## 1. Client → Gateway

### 1.1 POST `/api/meeting-audio/sessions`

**Authentication**: `Authorization: Bearer <jwt>` required.

**Headers**:

| Header | Required | Anlam |
|---|---|---|
| `Authorization` | ✅ | Bearer JWT |
| `X-Correlation-Id` | optional | Client-supplied trace ID (≤64 char). Yoksa Gateway üretir. |

**Body** (`application/json`):

```json
{
  "meetingId": "MTG-2026-0042",
  "deviceId": "iphone-h-12345",
  "language": "tr",
  "audioFormat": "WAV",
  "sampleRateHz": 16000,
  "channels": 1,
  "idempotencyKey": "uuid-v4-optional"
}
```

**Validation**:

| Field | Rule |
|---|---|
| `meetingId` | `^MTG-[0-9]{4}-[0-9]{1,8}$` |
| `deviceId` | `^[A-Za-z0-9._-]{1,64}$` |
| `language` | ISO 639-1 `^[a-z]{2}(-[A-Z]{2})?$` |
| `audioFormat` | `WAV` / `WEBM_OPUS` / `PCM16` (CLIENT_ALLOWED subset) |
| `sampleRateHz` | `16000` veya `48000` |
| `channels` | `1` (PoC mono only) |
| `idempotencyKey` | Optional ≤64 char |

**Response** (200 OK):

```json
{
  "sessionId": "SES-...",
  "correlationId": "...",
  "websocketUrl": "/api/meeting-audio/sessions/.../stream",
  "chunkUploadUrl": "/api/meeting-audio/sessions/.../chunks",
  "sessionStartMs": 1781820000123
}
```

**Errors** (canonical `ErrorResponse` body):

| Status | Code | Anlam |
|---|---|---|
| 400 | `AUDIO_GATEWAY_VALIDATION` | Field validation fail |
| 400 | `AUDIO_GATEWAY_FORMAT_REJECTED` | Sample rate / channels |
| 401 | `AUDIO_GATEWAY_AUTH_INVALID` | JWT decode/missing |
| 403 | `AUDIO_GATEWAY_MEETING_FORBIDDEN` | JWT missing `companyId` or `userId` claim |
| 415 | `AUDIO_GATEWAY_FORMAT_REJECTED` | Audio format outside CLIENT_ALLOWED |
| 429 | `AUDIO_GATEWAY_QUEUE_FULL` | Admission queue dolu (retry-after) |
| 503 | `AUDIO_GATEWAY_STT_UNAVAILABLE` | Downstream yok |

### 1.2 WS `/api/meeting-audio/sessions/{sessionId}/stream` (PR-gw-02)

Binary frame: `[1B version][8B chunkSeq][8B capturedAtMs][2B len][N B audio]`

### 1.3 POST `/sessions/{id}/chunks` (PR-gw-02) HTTP fallback

### 1.4 POST `/sessions/{id}/finish`

### 1.5 GET `/sessions/{id}/status`

---

## 2. Gateway → STT Internal

Client **NEVER** sends these:

| Internal Header | Source | Anlam |
|---|---|---|
| `X-Correlation-Id` | Gateway | Trace ID |
| `X-Meeting-Id` | Client body (validated) | Meeting binding |
| `X-Session-Id` | Gateway-generated | Per-recording session |
| `X-Device-Id` | Client body | Mobile device |
| `X-Tenant-Id` | **JWT claim** (default `companyId`, configurable `audio.gateway.jwt.tenant-claim`) | Multi-tenant; absent → 403 fail-closed |
| `X-User-Id` | **JWT claim** (default `userId`, configurable `audio.gateway.jwt.user-claim`) | Audit; absent → 403 fail-closed |
| `language` | Client body | ISO 639-1 |
| `audio_metadata` | Gateway JSON | format/sampleRate/channels/durationMs/chunkSeq |

**Critical**: `X-Tenant-Id` / `X-User-Id` **JWT-derived, NEVER client-trusted** (Codex RED).

**Claim naming** (Codex `019e8846` iter-1 absorb): backend canonical (`common-auth/AuthenticatedUserLookupService`) `companyId` (Long) + `userId` (Long). Custom realm override:

| Env | Default |
|---|---|
| `AUDIO_GATEWAY_JWT_TENANT_CLAIM` | `companyId` |
| `AUDIO_GATEWAY_JWT_USER_CLAIM` | `userId` |

Absent claim → **403 fail-closed** (sessiz "unknown" YASAK).

---

## 3. Bounds (Hard Limits)

| Limit | Default | Env Override |
|---|---|---|
| Max chunk bytes | 1 MB | `AUDIO_GATEWAY_MAX_CHUNK_BYTES` |
| Max buffered seconds | 30 sn | `AUDIO_GATEWAY_MAX_BUFFERED_SECONDS` |
| Max session minutes | 120 dk | `AUDIO_GATEWAY_MAX_SESSION_MINUTES` |
| Admission queue capacity | 1000 | `AUDIO_GATEWAY_QUEUE_CAPACITY` |

---

## 4. Backpressure

| Durum | Status | Retry-after |
|---|---|---|
| Queue full | `429` | header `Retry-After: 5` |
| STT unavailable | `503` | header `Retry-After: 30` |
| Maintenance | `503` + `AUDIO_GATEWAY_MAINTENANCE` | header `Retry-After: 60` |

Exponential backoff önerilir (1s, 2s, 4s, 8s, 16s).

---

## 5. Error Model

```json
{
  "code": "AUDIO_GATEWAY_FORMAT_REJECTED",
  "message": "Unsupported sample rate: 22050",
  "correlationId": "c0ffee...",
  "retryable": false,
  "details": {}
}
```

**Code namespace**: `AUDIO_GATEWAY_*`. Görünür enumlar: AUTH_INVALID, VALIDATION, FORMAT_REJECTED, OVERSIZE, QUEUE_FULL, STT_UNAVAILABLE, MEETING_FORBIDDEN, INTERNAL.

**PII guard**: `details` map'inde **YASAK** audio path/transcript text/user email raw/IP raw/bearer token/secret.

---

## 6. Audit Event Contract

```json
{
  "audit_id": "AUD-...",
  "timestamp": "...",
  "actor": { "user_id": "kc-sub-...", "user_email_hash": "...", "role": "meeting_owner" },
  "action": "audio_session.start",
  "resource": { "meeting_id": "MTG-2026-0042", "session_id": "SES-...", "tenant_id": "companyId-1" },
  "result": "ok",
  "client_ip_hash": "...",
  "user_agent_class": "mobile_react_native"
}
```

`action` enum: `audio_session.start`/`stream`/`finish`/`error`, `audio_chunk.admission_rejected`. KVKK Madde 12 7 yıl immutable.

---

## 7. Observability

Tüm metric/log/trace `X-Correlation-Id` ile propagate. PII guard: bearer/secret/email/TC kimlik/IBAN/phone regex-redacted (Mavis PR #74 platform-ai uyumlu).

Metric naming: `audio_gateway_*` namespace + cardinality guard (meeting_id_hash, user_hash_prefix(8)).

Tam liste: `platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md`.

---

## 8. Version Lifecycle

- **v1.0** FROZEN 2026-06-02 (PR-gw-01 v2)
- **v1.x** backward-compatible
- **v2** breaking change → ADR + Codex consensus + 6 ay overlap

---

## References

- ADR-0030 KVKK Meeting Intelligence Boundary
- Observability skeleton
- Faz 24 canonical plan
- Codex threads: `019e879c`, `019e8846`, `019e887c`
- Mavis msg `74` + `78`
- platform-ai PR #74 (Mavis Prometheus metrics + correlation + format normalisation + PII patterns)
- platform-ai issues #4-#13 (M1 anchor)
