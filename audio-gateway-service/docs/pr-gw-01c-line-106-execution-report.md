# PR-gw-01C Line #106 Execution Report

Issue: `#106 [PR-gw-01C] audio-gateway-service Redis Streams cross-server dispatcher producer (ADR-0031 §3.7)`

> Note: the `§3.7` in the issue *title* is stale — after the gitops PR #1289
> canonical sync (squash `f5e98f7`, merged 2026-06-05) ADR-0031 is structured as
> D1–D8. The cross-server contract lives in **ADR-0031 D2** (network topology),
> failure modes in **ADR-0031 D8**; the PII boundary is **ADR-0030
> §"Cross-Server STT Transit Boundary"**. The title is quoted verbatim above;
> all references below use the canonical D-sections.

Canonical source: live GitHub issue body (Halildeu/platform-ai #106) + the
2026-06-05 Codex 019e97bb iter-1 absorb comment (partition strategy correction).

## Objective

Implement `audio.gateway.dispatcher.mode=redis` — a Redis Streams producer variant
of `AudioChunkDispatcher` that forwards admitted audio chunks cross-server
(platform-backend audio-gateway → staging-sw Redis Streams → platform-ai
live-stt-service consumer, ADR-0031 D2 cross-server network topology; failure
modes per ADR-0031 D8).

## Requirement Mapping

| Issue requirement | Implementation |
|---|---|
| `RedisStreamsAudioChunkDispatcher` | New `@Service @Primary @ConditionalOnProperty(redis)` bean |
| Cross-server producer | `StringRedisTemplate` XADD to `audio:chunks:p00..p31` |
| `SUPPORTED_MODES_B3` `{"noop"}` → `{"noop","redis"}` | `AudioGatewayProperties.Dispatcher.SUPPORTED_MODES` extended |
| DispatchOutcome mapping | XADD ok → Accepted; `XLEN >= stream-max-len` → QueueFull(5s); Redis failure → Unavailable(30s) |
| Partition-based bucketing (Codex 019e97bb iter-1 absorb + ADR-0031 D3) | Stream key `audio:chunks:p<NN>`, `NN = hash(tenantId + sessionId) % partition-count` (default 32, zero-padded). Per-tenant keys (`meeting:chunks:{tenantId}`, issue body variant) rejected: 100+ tenants explode the keyspace and defeat `live-stt-v1` consumer-group horizontal scale |
| Idempotency `sessionId+chunkSeq` | Carried as `messageId` field (Redis entry IDs must be monotonic ms-seq) |
| PII guard (hash only) | Stream payload = SHA-256 + routing metadata; never raw audio/transcript |
| Audit emit on 429/503 | Already wired in `AudioSessionController` (B3); dispatcher only returns the outcome |

## Files

| File | Change |
|---|---|
| `config/AudioGatewayProperties.java` | `SUPPORTED_MODES` += `redis`; new `streamKeyPrefix` (default `audio:chunks:p`), `partitionCount` (default 32, validated in `[1,100]`), `streamMaxLen`, consumer-lag/failover knobs (`consumerGroup`, `consumerLagPendingThreshold`, `consumerLagRetryAfterSeconds`, `consumerIdleThresholdMs`, `failoverRetryAfterSeconds`) (+validation) |
| `service/RedisStreamsAudioChunkDispatcher.java` | New producer bean (partition-based `streamKeyFor()`, exception type-dispatch, XPENDING consumer-lag gate) |
| `pom.xml` | `spring-boot-starter-data-redis` |
| `test/.../RedisStreamsAudioChunkDispatcherTest.java` | 14 unit tests (6 original + 3 partition + 5 Codex error-scenario) |

## Design Notes

- **Partition-based stream keys (review iter-2 fix, P1-1):** the first
  iteration used per-tenant keys (`meeting:chunks:{tenantId}`) following the
  issue body; the issue's 2026-06-05 Codex absorb comment and ADR-0031 D3
  supersede that with 32 fixed partitions (`audio:chunks:p00..p31`,
  `hash(tenantId + sessionId) % partition-count`). Deterministic per session
  (dedup stays partition-local) while one tenant's sessions spread across
  partitions for consumer-group horizontal scale.
- **Backpressure, not trimming:** a full stream (`XLEN >= stream-max-len`) is
  rejected with QueueFull so unread backlog applies real backpressure. Consumer
  XACK/trim is live-stt-service scope (PR-stt-03).
- **Idempotency field:** Redis stream IDs are monotonic `ms-seq`, so the
  deterministic `sessionId:chunkSeq` is a `messageId` field for replay-safe
  consumer dedup; Redis assigns the physical entry ID.
- **Synchronous client:** the `dispatch()` port runs under the admission monitor,
  so a blocking `StringRedisTemplate` (Lettuce) is used, not the reactive client.
  The two round-trips (XLEN + XADD) are acceptable for the PoC; a per-session
  lock / outbox refactor is the documented high-throughput follow-up.
- **Bean selection:** `@ConditionalOnProperty(mode=redis)` + `@Primary` so the
  Redis bean is used only when configured; default stays `NoOpAudioChunkDispatcher`.
- **429/503 + Retry-After enumeration (review iter-2 fix, P2-1):** the Codex
  8-scenario table is implemented via exception type-dispatch + a producer-side
  XPENDING consumer-health gate: AUTH/ACL and mTLS failures → Unavailable(30s)
  with `ALERT`-level logs for ops routing; transient cluster failover
  (MOVED/CLUSTERDOWN/LOADING/TRYAGAIN) → Unavailable(5s); consumer-group lag
  beyond pending threshold or oldest pending idle beyond the idle threshold →
  QueueFull(10s). Pre-consumer phase (group absent) the XPENDING probe is
  swallowed and XLEN remains the only gate.
- **Success-path audit (review M-1, follow-up):** the
  `audio_chunk_forwarded_to_platform_ai` event (ADR-0030 §"Cross-Server STT
  Transit Boundary") is NOT yet emitted on the Accepted path — the B3 audit sink
  covers rejections only. Documented in the dispatcher Javadoc; extending the
  sink with a `ChunkForwardedToComputePlane` variant (per-session batch emit) is
  a follow-up item so the KVKK m.5/m.10 trail gap stays visible.

## Validation Evidence

| Check | Result |
|---|---|
| Compilation (Java 21, Spring Boot 3.5.6) | PASS (iter-1) |
| `RedisStreamsAudioChunkDispatcherTest` (iter-1, per-tenant keys) | 6 passed, 0 failures |
| — Accepted below capacity | PASS |
| — QueueFull at capacity (add not called) | PASS |
| — Unavailable on Redis `DataAccessException` | PASS |
| — payload carries SHA-256, not raw audio | PASS |
| — properties accept `redis`, reject unknown mode | PASS |
| **iter-2 (partition keys, 9 tests)** | **9 passed, 0 failures, 0 errors** (2026-06-10 16:26 +03, `mvnw test -Dtest=RedisStreamsAudioChunkDispatcherTest`, BUILD SUCCESS) |
| — partition-count validation rejects 0 / >100 | PASS |
| — partition deterministic per session | PASS |
| — one tenant's sessions spread across partitions | PASS |
| **iter-2b (P2-1 error scenarios, 14 tests total)** | **14 passed, 0 failures, 0 errors** (2026-06-10 16:48 +03, BUILD SUCCESS; ALERT-level logs observed for AUTH + mTLS scenarios) |
| — AUTH/ACL failure → Unavailable(30s) + ALERT | PASS |
| — mTLS handshake failure → Unavailable(30s) + ALERT | PASS |
| — cluster failover transient → Unavailable(5s) | PASS |
| — consumer lag > threshold → QueueFull(10s) | PASS |
| — consumer not draining (idle > threshold) → QueueFull(10s) | PASS |

### Environment limitation (not a code defect)

The existing `@SpringBootTest` contract tests (`*ContractTest`) start a real
Netty web server (`webEnvironment=RANDOM_PORT`). In the current sandbox they fail
to load context with:

```text
WebServerException: Unable to start Netty
IOException: Unable to establish loopback connection
SocketException: Invalid argument: connect
```

This was **proven pre-existing**: stashing all #106 changes and running
`StartSessionContractTest` on pristine code reproduces the identical Netty
loopback error. It is an environment networking restriction, unrelated to this
change. These tests run normally on a host/CI with loopback networking.

## Acceptance Gates — status

| Gate | Status |
|---|---|
| Network failure → Unavailable(503)+Retry-After 30 | ✅ unit-tested |
| Queue full → QueueFull(429)+Retry-After 5 | ✅ unit-tested |
| PII guard: hash-only payload | ✅ unit-tested |
| Container e2e (gateway+redis+live-stt) | ⛔ out of scope here — needs live-stt Redis consumer (PR-stt-03) + operator Redis/WireGuard (ADR-0031 D1 host boundary + D2 network) |
| Latency baseline (median/p99) | ⛔ needs the running cross-server topology |

## Out of Scope (per issue)

- Redis cluster setup → staging-sw operator (ADR-0031 D1 host boundary)
- live-stt-service Redis consumer → PR-stt-03
- Cross-server WireGuard tunnel → operator (ADR-0031 D2 network topology)

## Next Step

Provider-independent Cross-AI peer review is still required by repository policy
before any upstream merge. This report does not claim external review occurred.
Branch is on the contributor fork only; no upstream push.
