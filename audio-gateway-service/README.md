# audio-gateway-service

Faz 24 Meeting Intelligence — **Audio Gateway** (Spring Cloud Gateway WebFlux).

> **Status**: Contract 1.0 FROZEN (PR-gw-01 skeleton). Real STT dispatch / WS streaming / Redis producer sonraki PR'larda.
>
> **3-AI mutabakat**: Claude + Codex `019e879c` AGREE final + Mavis `mvs_c922...` msg `78` AGREE.

## Rol

**Tek ingress** Meeting Intelligence için:

```
[Mobile / Web] → audio-gateway-service → (Redis queue / HTTP adapter) → live-stt-service (platform-ai)
```

Mobile/Web hiçbir zaman platform-ai'a doğrudan bağlanmaz (Codex/Mavis RED).

## Scope (bu PR — minimum viable contract freeze)

- ✅ Maven module skeleton (Spring Boot 3.5.6 + Spring Cloud Gateway 2025.0.1 + Java 21)
- ✅ JWT validation (OAuth2 resource server, Keycloak realm reuse)
- ✅ Correlation ID propagation (`CorrelationIdWebFilter`)
- ✅ Audio Format whitelist (`WAV / WEBM_OPUS / PCM16` — client allowed subset)
- ✅ Sample rate enum (`16000 / 48000` Hz)
- ✅ Channels guard (mono only PoC)
- ✅ Error model canonical (`ErrorResponse` + namespace codes)
- ✅ STT dispatch interface (`SttDispatchService` + `NoOpSttDispatch` mock)
- ✅ Contract docs (`docs/contract-v1.md`)
- ✅ Contract tests (5 senaryo + correlation propagation)

## Non-goals

- ❌ Real STT inference (PR-stt-02 + PR-stt-03)
- ❌ Redis full producer/consumer (PR-queue-01)
- ❌ WS chunk streaming binary protocol (PR-gw-02)
- ❌ Meeting access check real impl (PR-gw-02 + meeting-service entegre)
- ❌ Mobile/Web UI (M6)
- ❌ WER / GPU / transcript storage / consent UI

## Build

```bash
cd audio-gateway-service
./mvnw clean test
./mvnw spring-boot:run
```

## Endpoints

| Method | Path | Status |
|---|---|---|
| POST | `/api/meeting-audio/sessions` | ✅ stub |
| WS | `/api/meeting-audio/sessions/{id}/stream` | ⏳ PR-gw-02 |
| POST | `/api/meeting-audio/sessions/{id}/chunks` | ⏳ HTTP fallback PR-gw-02 |
| POST | `/api/meeting-audio/sessions/{id}/finish` | ⏳ PR-gw-02 |
| GET | `/api/meeting-audio/sessions/{id}/status` | ⏳ PR-gw-02 |

## Config (env override)

| Variable | Default |
|---|---|
| `AUDIO_GATEWAY_JWT_ISSUER_URI` | `http://platform-keycloak:8080/realms/platform` |
| `AUDIO_GATEWAY_STT_DISPATCH` | `noop` |
| `AUDIO_GATEWAY_MAX_CHUNK_BYTES` | `1048576` (1 MB) |
| `AUDIO_GATEWAY_MAX_BUFFERED_SECONDS` | `30` |
| `AUDIO_GATEWAY_MAX_SESSION_MINUTES` | `120` (2 saat) |
| `AUDIO_GATEWAY_QUEUE_CAPACITY` | `1000` |

## Contract referans

[docs/contract-v1.md](./docs/contract-v1.md) — FROZEN 2026-06-02.

## Cross-AI Mutabakat

- Codex `019e879c-c51e-7691-8f16-69c781fb787e` (plan-time + iter-3 AGREE)
- Mavis `mvs_c922505d66a94a45b031feb3489f9488` msg `74` (PARTIAL) → msg `78` (AGREE)
- Claude (implementer) AGREE

## References

- Canonical plan: `platform-k8s-gitops/docs/faz-24-meeting-intelligence-plan.md`
- KVKK ADR: `platform-k8s-gitops/docs/adr/0030-kvkk-meeting-intelligence-boundary.md`
- Observability: `platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md`
- STT downstream: [`platform-ai/services/live-stt-service`](https://github.com/Halildeu/platform-ai)
