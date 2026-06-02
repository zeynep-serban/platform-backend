# Audio Gateway Contract v1.0

> **Status**: FROZEN (2026-06-02). 3-AI mutabakat sonrası canonical.
>
> Değişiklik = breaking change → yeni major version (v2) + ADR + Codex consensus.

## Mutabakat Trail

- Codex `019e879c-c51e-7691-8f16-69c781fb787e` AGREE final (Mavis 5 revize absorb)
- Mavis `mvs_c922505d66a94a45b031feb3489f9488` msg `78` AGREE
- Claude (implementer) AGREE

## Pozisyon

`audio-gateway-service` tek client-facing ingress. Mobile/Web ↔ `platform-ai` doğrudan bağlantı **YASAK**. Tüm istek bu Gateway'den geçer.

---

## 1. Client → Gateway

### 1.1 POST `/api/meeting-audio/sessions` — Start Session

**Authentication**: `Authorization: Bearer <jwt>` required (Keycloak realm `platform`).

**Headers (önerilen)**:

| Header | Required | Anlam |
|---|---|---|
| `Authorization` | ✅ | Bearer JWT |
| `X-Correlation-Id` | optional | Client-supplied trace ID (UUIDv4, ≤64 char). Yoksa Gateway üretir. |

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
| `meetingId` | `^MTG-[0-9]{4}-[0-9]{1,8}$` (e.g. `MTG-2026-0042`) |
| `deviceId` | Opaque token `^[A-Za-z0-9._-]{1,64}$` |
| `language` | ISO 639-1 `^[a-z]{2}(-[A-Z]{2})?$` (`tr`, `en`, `de`, `tr-TR`) |
| `audioFormat` | Enum: `WAV` / `WEBM_OPUS` / `PCM16` (client-allowed subset) |
| `sampleRateHz` | `16000` veya `48000` |
| `channels` | `1` (PoC mono only) |
| `idempotencyKey` | Optional, ≤64 char (session start dedup) |

**Response** (200 OK):

```json
{
  "sessionId": "SES-7a8b9c0d-...",
  "correlationId": "c0ffee...",
  "websocketUrl": "/api/meeting-audio/sessions/SES-.../stream",
  "chunkUploadUrl": "/api/meeting-audio/sessions/SES-.../chunks",
  "sessionStartMs": 1781820000123
}
```

**Errors**:

| Status | Code | Anlam |
|---|---|---|
| 400 | `AUDIO_GATEWAY_LANGUAGE_REQUIRED` | `language` field eksik |
| 400 | `AUDIO_GATEWAY_VALIDATION` | Diğer validation hatası |
| 401 | `AUDIO_GATEWAY_AUTH_INVALID` | JWT decode/signature fail |
| 403 | `AUDIO_GATEWAY_MEETING_FORBIDDEN` | Meeting access yok |
| 415 | `AUDIO_GATEWAY_FORMAT_REJECTED` | Audio format/sample rate desteklenmiyor |
| 429 | `AUDIO_GATEWAY_QUEUE_FULL` | Admission queue dolu (retry-after) |
| 503 | `AUDIO_GATEWAY_STT_UNAVAILABLE` | Downstream STT yok |

### 1.2 WS `/api/meeting-audio/sessions/{sessionId}/stream` — Audio Stream (PR-gw-02 scope)

Binary frame protocol — chunk-by-chunk. Frame header:

```
[1 byte version][8 byte chunkSeq monotonic][8 byte capturedAtMs][2 byte length][N byte audio]
```

### 1.3 POST `/api/meeting-audio/sessions/{sessionId}/chunks` — HTTP fallback (PR-gw-02)

Multipart upload for clients without WS support.

### 1.4 POST `/api/meeting-audio/sessions/{sessionId}/finish`

Mark session as complete; trigger final-stt-service flush.

### 1.5 GET `/api/meeting-audio/sessions/{sessionId}/status`

Returns: `{ status: STARTED | STREAMING | FINISHING | FINISHED | ERROR, chunkCount, lastChunkSeq, durationMs }`.

---

## 2. Gateway → STT Internal

Gateway derives identity from JWT and propagates internal headers to STT services. Client **NEVER** sends these:

| Internal Header | Source | Anlam |
|---|---|---|
| `X-Correlation-Id` | Gateway (client OR generated) | Trace ID |
| `X-Meeting-Id` | Client request body (validated against meeting access) | Meeting binding |
| `X-Session-Id` | Gateway-generated | Per-recording session |
| `X-Device-Id` | Client request body | Mobile device |
| `X-Tenant-Id` | **JWT claim** (default `companyId`, configurable `audio.gateway.jwt.tenant-claim`) | Multi-tenant boundary; absent → 403 fail-closed |
| `X-User-Id` | **JWT claim** (default `userId`, configurable `audio.gateway.jwt.user-claim`) | Audit trail; absent → 403 fail-closed |
| `language` | Client request body (forwarded) | ISO 639-1 |
| `audio_metadata` | Gateway-built JSON: `{format, sampleRateHz, channels, durationMs, chunkSeq}` | Per-chunk meta |

**Critical**: `X-Tenant-Id` / `X-User-Id` are **JWT-derived, NEVER client-trusted**. Client payload `tenantId` field rejected silently (Codex `019e879c` RED).

**Claim naming** (Codex `019e8846` iter-1 absorb): backend canonical pattern (`common-auth/AuthenticatedUserLookupService`) uses `companyId` (Long, multi-tenant) + `userId` (Long). Defaults:

| Env | Default |
|---|---|
| `AUDIO_GATEWAY_JWT_TENANT_CLAIM` | `companyId` |
| `AUDIO_GATEWAY_JWT_USER_CLAIM` | `userId` |

Custom Keycloak realm uses other names → override via env. **Absent claim → fail-closed 403** (sessiz "unknown" YASAK).

---

## 3. Bounds (Hard Limits)

| Limit | Default | Env Override |
|---|---|---|
| Max chunk bytes | 1 MB (1048576) | `AUDIO_GATEWAY_MAX_CHUNK_BYTES` |
| Max buffered seconds | 30 sn | `AUDIO_GATEWAY_MAX_BUFFERED_SECONDS` |
| Max session minutes | 120 dk (2 saat) | `AUDIO_GATEWAY_MAX_SESSION_MINUTES` |
| Admission queue capacity | 1000 | `AUDIO_GATEWAY_QUEUE_CAPACITY` |

Bound aşımı → deterministik error response (PII'sız).

---

## 4. Backpressure

| Durum | Status | Retry-after |
|---|---|---|
| Queue full | `429 Too Many Requests` | header `Retry-After: 5` |
| STT unavailable | `503 Service Unavailable` | header `Retry-After: 30` |
| Maintenance window | `503` + `code=AUDIO_GATEWAY_MAINTENANCE` | header `Retry-After: 60` |

Client backoff bekler. Exponential retry önerilir (1s, 2s, 4s, 8s, 16s).

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

**Code namespace**: `AUDIO_GATEWAY_*` prefix tüm error code'larda. Görünür enum'lar:

- `AUDIO_GATEWAY_AUTH_INVALID` (401)
- `AUDIO_GATEWAY_LANGUAGE_REQUIRED` (400)
- `AUDIO_GATEWAY_VALIDATION` (400)
- `AUDIO_GATEWAY_FORMAT_REJECTED` (415)
- `AUDIO_GATEWAY_OVERSIZE` (413)
- `AUDIO_GATEWAY_QUEUE_FULL` (429)
- `AUDIO_GATEWAY_STT_UNAVAILABLE` (503)
- `AUDIO_GATEWAY_MEETING_FORBIDDEN` (403)
- `AUDIO_GATEWAY_INTERNAL` (500)

**PII guard**: `details` map'inde **YASAK**:
- audio path / full filename
- transcript text / partial transcript
- user email raw (hash or omit)
- IP raw (hash)
- bearer token / secret

---

## 6. Audit Event Contract

Gateway emit eder her session lifecycle event:

```json
{
  "audit_id": "AUD-...",
  "timestamp": "...",
  "actor": { "user_id": "kc-sub-...", "user_email_hash": "...", "role": "meeting_owner" },
  "action": "audio_session.start",
  "resource": { "meeting_id": "MTG-2026-0042", "session_id": "SES-...", "tenant_id": "workcube-main" },
  "result": "ok",
  "client_ip_hash": "...",
  "user_agent_class": "mobile_react_native"
}
```

`action` enum:
- `audio_session.start`
- `audio_session.stream`
- `audio_session.finish`
- `audio_session.error`
- `audio_chunk.admission_rejected`

KVKK Madde 12 7 yıl immutable retention (PR-audit-01).

---

## 7. Observability

Tüm metric / log / trace `X-Correlation-Id` ile propagate. PII guard structured log değerlerinde regex-redacted (bearer/secret/email).

Metric naming: `audio_gateway_*` namespace (cardinality guard: meeting_id_hash, user_hash_prefix(8)).

Tam liste: `platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md`.

---

## 8. Version Lifecycle

- **v1.0** FROZEN 2026-06-02 (PR-gw-01)
- **v1.x** — backward-compatible (yeni optional field, yeni endpoint)
- **v2** — breaking change (ADR + Codex consensus + migration plan + min 6 ay overlap)

Client-facing breaking change YASAK v1 lifetime'da.

---

## References

- ADR-0030 KVKK Meeting Intelligence Boundary
- Observability skeleton (`docs/observability-skeleton-meeting-intelligence.md`)
- Faz 24 canonical plan (`docs/faz-24-meeting-intelligence-plan.md`)
- Codex thread `019e879c-c51e-7691-8f16-69c781fb787e`
- Mavis msg `78` AGREE
- platform-ai issue #4 ([PR-gw-01] audio-gateway-service module skeleton)
- platform-ai issue #5 ([PR-gw-01] Client → Gateway contract markdown)
- platform-ai issue #6 ([PR-gw-01] Gateway → STT internal contract)
