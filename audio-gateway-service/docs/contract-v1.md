# Audio Gateway Contract v1.0

> **Status**: REVISION 2026-06-03 (PR-gw-01A normalize) — ADR-0031 two-server topology + Codex `019e8c26` iter-2 AGREE PR-gw-01A absorb. "FROZEN" iddiası bu PR squash merge sonrası geçerli olur.
>
> Değişiklik = breaking change → yeni major version (v2) + ADR + Codex consensus.

## Mutabakat Trail

- Codex `019e879c-c51e-7691-8f16-69c781fb787e` AGREE final (Mavis 5 revize absorb)
- Codex `019e8846` iter-1 absorb (mockJwt + canonical ErrorResponse + companyId/userId claim)
- Codex `019e8c26-c8ee-7610-8e96-a40bfe62a45a` iter-2 AGREE PR-gw-01A (path canonical + Idempotency-Key header + AudioSessionRegistry + slice boundary)
- ADR-0031 two-server topology (`platform-k8s-gitops/docs/adr/0031-...`) ACCEPTED 2026-06-03
- Mavis `mvs_c922505d66a94a45b031feb3489f9488` msg `78` AGREE (post-availability non-blocking)
- Claude (implementer) AGREE

## Pozisyon

`audio-gateway-service` tek client-facing ingress. Mobile/Web ↔ `platform-ai` doğrudan bağlantı **YASAK**. Tüm istek bu Gateway'den geçer.

**ADR-0031 two-server topology** (Codex `019e8c09` AGREE final):

- **staging-sw orchestration plane**: audio-gateway-service + meeting-service + transcript-service + Faz 22-23 + Redis bounded queue (admission/policy Gateway boundary)
- **platform-ai compute plane**: live-stt-service (Python STT) — ayrı dedicated host
- Cross-server kanal: **WireGuard + mTLS PKI ZORUNLU** (private LAN yetmez, KVKK Madde 6/9 transit)
- Redis bucketed Streams: 32-partition + consumer group `live-stt-v1` (PR-gw-01C scope)

---

## 1. Client → Gateway

### 1.1 POST `/api/v1/audio-gateway/sessions` — Start Session (PR-gw-01A LIVE)

**Authentication**: `Authorization: Bearer <jwt>` required (Keycloak realm `platform`).

**Headers** (Codex `019e8c26` iter-2 AGREE):

| Header | Required | Anlam |
|---|---|---|
| `Authorization` | ✅ | Bearer JWT |
| `Idempotency-Key` | ✅ | **Opaque token 16-128 char** (`[A-Za-z0-9._:-]`). UUIDv4 tavsiye ama zorunlu değil. Scope: `tenantId + userId + "POST" + "/sessions" + key`. Same key + same effective request → 200 OK (replay). Same key + materially different request → 409 `AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT`. |
| `X-Correlation-Id` | optional | Client-supplied trace ID (UUIDv4, ≤64 char). Yoksa Gateway üretir. |

**Body** (`application/json`) — `idempotencyKey` body field **KALDIRILDI** (Codex iter-2: header canonical):

```json
{
  "meetingId": "MTG-2026-0042",
  "deviceId": "iphone-h-12345",
  "language": "tr",
  "audioFormat": "WAV",
  "sampleRateHz": 16000,
  "channels": 1
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
| `Idempotency-Key` header | 16-128 char `[A-Za-z0-9._:-]` opaque token |

**Response** (201 Created on fresh, 200 OK on replay):

```json
{
  "sessionId": "SES-7a8b9c0d-...",
  "correlationId": "c0ffee...",
  "websocketUrl":   "/api/v1/audio-gateway/sessions/SES-.../stream",
  "chunkUploadUrl": "/api/v1/audio-gateway/sessions/SES-.../chunks",
  "statusUrl":      "/api/v1/audio-gateway/sessions/SES-.../status",
  "finishUrl":      "/api/v1/audio-gateway/sessions/SES-.../finish",
  "sessionStartMs": 1781820000123
}
```

**Planned contract notes** (PR-gw-01A scope):

- `statusUrl` / `finishUrl` LIVE (PR-gw-01A).
- `websocketUrl` "planned contract" — functional implementation **PR-gw-01D**.
- `chunkUploadUrl` "planned contract" — functional implementation **PR-gw-01B**.

**Errors**:

| Status | Code | Anlam |
|---|---|---|
| 400 | `AUDIO_GATEWAY_IDEMPOTENCY_MISSING` | `Idempotency-Key` header eksik |
| 400 | `AUDIO_GATEWAY_IDEMPOTENCY_INVALID` | Key 16-128 char policy ihlal |
| 400 | `AUDIO_GATEWAY_VALIDATION` | Body validation (jakarta.validation) |
| 401 | `AUDIO_GATEWAY_AUTH_INVALID` | JWT decode/signature fail |
| 403 | `AUDIO_GATEWAY_MEETING_FORBIDDEN` | Tenant/user claim eksik veya meeting access yok |
| 409 | `AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT` | Same key + materially different request |
| 415 | `AUDIO_GATEWAY_FORMAT_REJECTED` | Audio format / sample rate / channels desteklenmiyor |
| 503 | `AUDIO_GATEWAY_SESSION_REGISTRY_FULL` | In-memory registry cap aşıldı (retry-after) |

### 1.2 GET `/api/v1/audio-gateway/sessions/{sessionId}/status` — Session State (PR-gw-01A LIVE)

**Response** (200 OK):

```json
{
  "sessionId": "SES-...",
  "correlationId": "c0ffee...",
  "state": "STARTED",
  "chunkCount": 0,
  "lastChunkSeq": 0,
  "durationMs": 1234,
  "sessionStartMs": 1781820000123,
  "updatedAtMs": 1781820001357
}
```

**PR-gw-01A scope**: `state` ∈ {`STARTED`, `FINISHING`, `FINISHED`}. `chunkCount` / `lastChunkSeq` always `0` (chunk admission **PR-gw-01B/C**). `durationMs` = `now − sessionStartMs` (active) veya `finishedAtMs − sessionStartMs` (terminal).

**Errors**:

| Status | Code | Anlam |
|---|---|---|
| 401 | `AUDIO_GATEWAY_AUTH_INVALID` | JWT missing |
| 403 | `AUDIO_GATEWAY_MEETING_FORBIDDEN` | Tenant/user claim eksik |
| 404 | `AUDIO_GATEWAY_SESSION_NOT_FOUND` | Session yok veya farklı tenant/user |

### 1.3 POST `/api/v1/audio-gateway/sessions/{sessionId}/finish` — Terminal Lifecycle (PR-gw-01A LIVE)

**Codex `019e8c26` iter-2**: "Terminal lifecycle event + dispatcher flush contract" — bu slice transcript üretimi vadetmez; sadece state transition + idempotent finish. Gerçek dispatcher flush PR-gw-01C Redis Streams producer'la gelir.

**Headers**: `Idempotency-Key` (zorunlu, 16-128 char) + `Authorization: Bearer <jwt>`.

**Body**: empty (PR-gw-01A; PR-gw-01C'de optional metadata flush hint olabilir).

**Response** (200 OK):

```json
{
  "sessionId": "SES-...",
  "correlationId": "c0ffee...",
  "finalState": "FINISHED",
  "finishedAtMs": 1781820999999,
  "alreadyFinished": false
}
```

**Idempotency semantics** (Codex iter-2 AGREE):

- 1st finish with key K → 200 OK, `alreadyFinished=false`, state `FINISHED`
- 2nd finish with same K → 200 OK, `alreadyFinished=true` (replay)
- 2nd finish with different K on already-FINISHED → 409 `AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT`

**Errors**:

| Status | Code | Anlam |
|---|---|---|
| 400 | `AUDIO_GATEWAY_IDEMPOTENCY_MISSING` / `INVALID` | Header policy |
| 401 | `AUDIO_GATEWAY_AUTH_INVALID` | JWT missing |
| 403 | `AUDIO_GATEWAY_MEETING_FORBIDDEN` | Owner mismatch (tenant/user) |
| 404 | `AUDIO_GATEWAY_SESSION_NOT_FOUND` | Session yok |
| 409 | `AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT` | Same session + different finish key on already-FINISHED |

### 1.4 WS `/api/v1/audio-gateway/sessions/{sessionId}/stream` — Audio Stream (**PR-gw-01D planned**)

Binary frame protocol — chunk-by-chunk. Frame header:

```
[1 byte version][8 byte chunkSeq monotonic][8 byte capturedAtMs][2 byte length][N byte audio]
```

**Bu slice'da implementation YOK** — surface URL'i `StartSessionResponse`'ta döndürülür ama endpoint deterministik 404 / not-yet-implemented (PR-gw-01D).

### 1.5 POST `/api/v1/audio-gateway/sessions/{sessionId}/chunks` — REST Fallback (**PR-gw-01B planned**)

Multipart upload for clients without WS support. **Bu slice'da implementation YOK** — surface URL'i döndürülür ama endpoint deterministik 404 / not-yet-implemented (PR-gw-01B).

---

## 2. Gateway → STT Internal

Gateway derives identity from JWT and propagates internal headers to STT services. Client **NEVER** sends these:

| Internal Header | Source | Anlam |
|---|---|---|
| `X-Correlation-Id` | Gateway (client OR generated) | Trace ID |
| `X-Meeting-Id` | Client request body (validated against meeting access) | Meeting binding |
| `X-Session-Id` | Gateway-generated | Per-recording session |
| `X-Device-Id` | Client request body | Mobile device |
| `X-Tenant-Id` | **JWT claim** `companyId` (configurable) | Multi-tenant boundary; absent → 403 fail-closed |
| `X-User-Id` | **JWT claim** `userId` (configurable) | Audit trail; absent → 403 fail-closed |
| `X-Subject-Id` | **JWT `sub` claim** (audit/debug only — tenant boundary için kullanılmaz) | Codex `019e8c26` iter-2 |
| `language` | Client request body (forwarded) | ISO 639-1 |
| `audio_metadata` | Gateway-built JSON: `{format, sampleRateHz, channels, durationMs, chunkSeq}` | Per-chunk meta |

**Critical (Codex `019e879c` RED + `019e8c26` iter-2)**: Client-supplied `X-Tenant-Id` / `X-User-Id` / `X-Session-Id` / `X-Meeting-Id` headers **strip edilir**, Gateway-derived değerler overwrite eder. Code assert **PR-gw-01E** hardening slice'da; bu slice'da doc-level guarantee.

**Claim naming** (Codex `019e8846` iter-1 + `019e8c26` iter-2 absorb): backend canonical pattern (`common-auth/AuthenticatedUserLookupService`) uses `companyId` (Long, multi-tenant) + `userId` (Long). Defaults:

| Env | Default |
|---|---|
| `AUDIO_GATEWAY_JWT_TENANT_CLAIM` | `companyId` |
| `AUDIO_GATEWAY_JWT_USER_CLAIM` | `userId` |

Custom Keycloak realm uses other names → override via env. **Absent claim → fail-closed 403** (sessiz "unknown" YASAK).

**Cross-server transit** (ADR-0031 §D2 + ADR-0030 §"Cross-Server STT Transit Boundary"): Gateway → live-stt cross-server hop **WireGuard + mTLS PKI ZORUNLU**. Private LAN yalnız synthetic/local Docker e2e PoC için geçici (gerçek meeting audio için kabul edilmez). Redis Streams bucketed 32-partition staging-sw'da (admission/policy Gateway boundary), live-stt platform-ai'dan consume eder.

---

## 3. Bounds (Hard Limits) — ADR-0031 PoC scope

| Limit | Default | Env Override |
|---|---|---|
| Max chunk bytes | **256 KB (262144)** | `AUDIO_GATEWAY_BOUNDS_MAX_CHUNK_BYTES` |
| Max buffered seconds | 30 sn | `AUDIO_GATEWAY_BOUNDS_MAX_BUFFERED_SECONDS` |
| Max session minutes | **60 dk** | `AUDIO_GATEWAY_BOUNDS_MAX_SESSION_MINUTES` |
| Admission queue capacity | 1000 | `AUDIO_GATEWAY_BOUNDS_ADMISSION_QUEUE_CAPACITY` |
| Max active sessions (in-memory) | 1000 | `AUDIO_GATEWAY_BOUNDS_MAX_ACTIVE_SESSIONS` |

**ADR-0031 PoC scope update**: chunk 1 MB → 256 KB; session 120 dk → 60 dk (Codex `019e8c26` iter-2 absorb).

Bound aşımı → deterministik error response (PII'sız).

---

## 4. Backpressure

| Durum | Status | Retry-after |
|---|---|---|
| Session registry full (in-memory cap) | `503 Service Unavailable` + `AUDIO_GATEWAY_SESSION_REGISTRY_FULL` | header `Retry-After: 5` |
| Queue full (PR-gw-01B/C scope) | `429 Too Many Requests` | header `Retry-After: 5` |
| STT unavailable (PR-gw-01C scope) | `503 Service Unavailable` | header `Retry-After: 30` |
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
- `AUDIO_GATEWAY_IDEMPOTENCY_MISSING` (400) — **PR-gw-01A**
- `AUDIO_GATEWAY_IDEMPOTENCY_INVALID` (400) — **PR-gw-01A**
- `AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT` (409) — **PR-gw-01A**
- `AUDIO_GATEWAY_FORMAT_REJECTED` (415)
- `AUDIO_GATEWAY_OVERSIZE` (413) — PR-gw-01B
- `AUDIO_GATEWAY_QUEUE_FULL` (429) — PR-gw-01B/C
- `AUDIO_GATEWAY_STT_UNAVAILABLE` (503) — PR-gw-01C
- `AUDIO_GATEWAY_SESSION_NOT_FOUND` (404) — **PR-gw-01A**
- `AUDIO_GATEWAY_SESSION_REGISTRY_FULL` (503) — **PR-gw-01A**
- `AUDIO_GATEWAY_MEETING_FORBIDDEN` (403)
- `AUDIO_GATEWAY_INTERNAL` (500)

**PII guard**: `details` map'inde **YASAK**:
- audio path / full filename
- transcript text / partial transcript
- user email raw (hash or omit)
- IP raw (hash)
- bearer token / secret

PII guard code assert **PR-gw-01E** hardening slice'da; bu slice'da doc-level guarantee.

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
- `audio_session.start` (PR-gw-01A)
- `audio_session.finish` (PR-gw-01A)
- `audio_chunk.admission_rejected` (PR-gw-01B/C)
- `audio_chunk_forwarded_to_platform_ai` (ADR-0030 §"Cross-Server STT Transit Boundary"; PR-gw-01C)
- `audio_session.error`

KVKK Madde 12 7 yıl immutable retention (PR-audit-01).

---

## 7. Observability

Tüm metric / log / trace `X-Correlation-Id` ile propagate. PII guard structured log değerlerinde regex-redacted (bearer/secret/email).

Metric naming: `audio_gateway_*` namespace (cardinality guard: meeting_id_hash, user_hash_prefix(8)).

Tam liste: `platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md`.

---

## 8. Version Lifecycle

- **v1.0 REVISION** 2026-06-03 (PR-gw-01A — path canonical + Idempotency-Key + AudioSessionRegistry)
- **v1.0 FROZEN** ⏳ PR-gw-01 series complete (A/B/C/D/E merged)
- **v1.x** — backward-compatible (yeni optional field, yeni endpoint)
- **v2** — breaking change (ADR + Codex consensus + migration plan + min 6 ay overlap)

Client-facing breaking change YASAK v1 lifetime'da.

---

## 9. PR Slice Roadmap (Codex `019e8c26` iter-2 AGREE)

| Slice | Scope | Status |
|---|---|---|
| **PR-gw-01A** | Path normalize + module/POM + Idempotency-Key header + AudioSessionRegistry interface + InMemory impl + start/status/finish skeleton + canonical error envelope + contract doc revision | 🟡 **bu PR** |
| PR-gw-01B | REST chunk admission (`POST /chunks`, max 256 KB, format whitelist config, 413/429/503, dispatcher mock interface) | ⏳ planned |
| PR-gw-01C | Redis Streams producer (bucketed 32-partition `audio:chunks:p00..p31` + consumer group `live-stt-v1` + XADD fields + bounds + idempotency `(sessionId, chunkSeq)`) | ⏳ planned |
| PR-gw-01D | WebSocket stream (binary + JSON metadata frames + unauthorized handshake + unknown session close + Redis Stream trim) | ⏳ planned |
| PR-gw-01E | Contract hardening (client X-* strip code assert + PII guard error payload + invalid state transition matrix + duplicate/out-of-order chunkSeq) | ⏳ planned |

---

## References

- ADR-0030 KVKK Meeting Intelligence Boundary + §"Cross-Server STT Transit Boundary"
- **ADR-0031 Two-Server Meeting Intelligence Topology** (`platform-k8s-gitops/docs/adr/0031-...`)
- Observability skeleton (`docs/observability-skeleton-meeting-intelligence.md`)
- Faz 24 canonical plan (`docs/faz-24-meeting-intelligence-plan.md`)
- Codex thread `019e879c` (Faz 24 plan iter-3 AGREE)
- Codex thread `019e8846` (mockJwt + canonical envelope iter-1)
- Codex thread `019e8c09` (ADR-0031 iter-4 AGREE final)
- Codex thread `019e8c26` (PR-gw-01A iter-2 AGREE)
- Mavis msg `78` AGREE
- platform-ai issue #4-13 PR-gw-01 contract spec (Done)
