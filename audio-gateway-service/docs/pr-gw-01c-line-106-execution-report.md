# PR-gw-01C Line #106 Execution Report

Issue: `#106 [PR-gw-01C] audio-gateway-service Redis Streams cross-server dispatcher producer (ADR-0031 §3.7)`

Canonical source: live GitHub issue body (Halildeu/platform-ai #106).

## Objective

Implement `audio.gateway.dispatcher.mode=redis` — a Redis Streams producer variant
of `AudioChunkDispatcher` that forwards admitted audio chunks cross-server
(platform-backend audio-gateway → staging-sw Redis Streams → platform-ai
live-stt-service consumer, ADR-0031 §3.7).

## Requirement Mapping

| Issue requirement | Implementation |
|---|---|
| `RedisStreamsAudioChunkDispatcher` | New `@Service @Primary @ConditionalOnProperty(redis)` bean |
| Cross-server producer | `StringRedisTemplate` XADD to `meeting:chunks:{tenantId}` |
| `SUPPORTED_MODES_B3` `{"noop"}` → `{"noop","redis"}` | `AudioGatewayProperties.Dispatcher.SUPPORTED_MODES` extended |
| DispatchOutcome mapping | XADD ok → Accepted; `XLEN >= stream-max-len` → QueueFull(5s); Redis failure → Unavailable(30s) |
| Per-tenant bucketing | Stream key `meeting:chunks:{tenantId}` |
| Idempotency `sessionId+chunkSeq` | Carried as `messageId` field (Redis entry IDs must be monotonic ms-seq) |
| PII guard (hash only) | Stream payload = SHA-256 + routing metadata; never raw audio/transcript |
| Audit emit on 429/503 | Already wired in `AudioSessionController` (B3); dispatcher only returns the outcome |

## Files

| File | Change |
|---|---|
| `config/AudioGatewayProperties.java` | `SUPPORTED_MODES` += `redis`; new `streamKeyPrefix`, `streamMaxLen` (+validation) |
| `service/RedisStreamsAudioChunkDispatcher.java` | New producer bean |
| `pom.xml` | `spring-boot-starter-data-redis` |
| `test/.../RedisStreamsAudioChunkDispatcherTest.java` | 6 unit tests |

## Design Notes

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

## Validation Evidence

| Check | Result |
|---|---|
| Compilation (Java 21, Spring Boot 3.5.6) | PASS |
| `RedisStreamsAudioChunkDispatcherTest` | **6 passed, 0 failures** |
| — Accepted below capacity | PASS |
| — QueueFull at capacity (add not called) | PASS |
| — Unavailable on Redis `DataAccessException` | PASS |
| — payload carries SHA-256, not raw audio | PASS |
| — properties accept `redis`, reject unknown mode | PASS |

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
| Container e2e (gateway+redis+live-stt) | ⛔ out of scope here — needs live-stt Redis consumer (PR-stt-03) + operator Redis/WireGuard (ADR-0031 §3.6/§3.7) |
| Latency baseline (median/p99) | ⛔ needs the running cross-server topology |

## Out of Scope (per issue)

- Redis cluster setup → staging-sw operator (ADR-0031 §3.7)
- live-stt-service Redis consumer → PR-stt-03
- Cross-server WireGuard tunnel → operator (ADR-0031 §3.6)

## Next Step

Provider-independent Cross-AI peer review is still required by repository policy
before any upstream merge. This report does not claim external review occurred.
Branch is on the contributor fork only; no upstream push.
