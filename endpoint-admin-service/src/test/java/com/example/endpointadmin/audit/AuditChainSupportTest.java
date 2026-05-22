package com.example.endpointadmin.audit;

import com.example.endpointadmin.model.EndpointAuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-016 — Unit tests for the audit hash-chain canonicalizer + hasher
 * (Codex 019e4f8e). Pure JUnit, no Spring context.
 */
class AuditChainSupportTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private EndpointAuditEvent sampleEvent() {
        EndpointAuditEvent event = new EndpointAuditEvent();
        event.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setTenantId(TENANT);
        event.setEventType("MAINTENANCE_TOKEN_CREATED");
        event.setAction("CREATE_MAINTENANCE_TOKEN");
        event.setPerformedBySubject("admin@example.com");
        event.setCorrelationId("corr-1");
        event.setOccurredAt(AuditChainSupport.normalizeTimestamp(
                Instant.parse("2026-05-22T09:52:15.123456Z")));
        // BE-016 (Codex 019e4f8e P2-3): alg/version are part of the canonical
        // payload — read from the event, not constants — so they must be set.
        event.setEventHashAlg(AuditChainSupport.HASH_ALGORITHM);
        event.setEventHashVersion(AuditChainSupport.HASH_VERSION);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tokenId", "abc");
        metadata.put("action", "STOP_AGENT");
        event.setMetadata(metadata);
        return event;
    }

    @Test
    void canonicalPayloadIsDeterministic() {
        String first = AuditChainSupport.canonicalPayload(sampleEvent());
        String second = AuditChainSupport.canonicalPayload(sampleEvent());
        assertThat(first).isEqualTo(second);
    }

    @Test
    void canonicalPayloadIgnoresMetadataInsertionOrder() {
        EndpointAuditEvent a = sampleEvent();
        EndpointAuditEvent b = sampleEvent();
        // b's metadata built in the opposite insertion order — sorted-key
        // canonicalization must yield an identical payload.
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("action", "STOP_AGENT");
        reordered.put("tokenId", "abc");
        b.setMetadata(reordered);
        assertThat(AuditChainSupport.canonicalPayload(a))
                .isEqualTo(AuditChainSupport.canonicalPayload(b));
    }

    @Test
    void computeEventHashIsDeterministicAndLowercaseHex() {
        String hash1 = AuditChainSupport.computeEventHash(null, sampleEvent());
        String hash2 = AuditChainSupport.computeEventHash(null, sampleEvent());
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void changedFieldChangesHash() {
        String baseline = AuditChainSupport.computeEventHash(null, sampleEvent());
        EndpointAuditEvent mutated = sampleEvent();
        mutated.setAction("TAMPERED_ACTION");
        assertThat(AuditChainSupport.computeEventHash(null, mutated))
                .isNotEqualTo(baseline);
    }

    @Test
    void changedMetadataValueChangesHash() {
        String baseline = AuditChainSupport.computeEventHash(null, sampleEvent());
        EndpointAuditEvent mutated = sampleEvent();
        Map<String, Object> tampered = new LinkedHashMap<>();
        tampered.put("tokenId", "abc");
        tampered.put("action", "TAMPERED");
        mutated.setMetadata(tampered);
        assertThat(AuditChainSupport.computeEventHash(null, mutated))
                .isNotEqualTo(baseline);
    }

    @Test
    void differentPrevHashChangesEventHash() {
        EndpointAuditEvent event = sampleEvent();
        String genesisHash = AuditChainSupport.computeEventHash(null, event);
        String linkedHash = AuditChainSupport.computeEventHash(
                "abc123def456abc123def456abc123def456abc123def456abc123def456abcd", event);
        // Same payload, different prev → different chained hash.
        assertThat(linkedHash).isNotEqualTo(genesisHash);
    }

    @Test
    void nullPrevAndGenesisSentinelProduceSameHash() {
        EndpointAuditEvent event = sampleEvent();
        // null prev and explicit blank both map to the GENESIS sentinel.
        assertThat(AuditChainSupport.computeEventHash(null, event))
                .isEqualTo(AuditChainSupport.computeEventHash("", event));
    }

    @Test
    void normalizeTimestampTruncatesToMicros() {
        Instant nanos = Instant.parse("2026-05-22T09:52:15.123456789Z");
        Instant normalized = AuditChainSupport.normalizeTimestamp(nanos);
        assertThat(normalized).isEqualTo(Instant.parse("2026-05-22T09:52:15.123456Z"));
    }

    @Test
    void hashColumnsAreNotPartOfCanonicalPayload() {
        EndpointAuditEvent event = sampleEvent();
        String before = AuditChainSupport.canonicalPayload(event);
        // Setting the hash columns must NOT change the canonical payload —
        // chain linkage is an outer composition, not a payload field.
        event.setEventHash("deadbeef".repeat(8));
        event.setPrevEventHash("cafebabe".repeat(8));
        assertThat(AuditChainSupport.canonicalPayload(event)).isEqualTo(before);
    }

    @Test
    void changedEventHashAlgChangesHash() {
        // BE-016 (Codex 019e4f8e P2-3): alg is hashed from the STORED value, so
        // tampering it must change the computed hash (verifier detects it).
        String baseline = AuditChainSupport.computeEventHash(null, sampleEvent());
        EndpointAuditEvent mutated = sampleEvent();
        mutated.setEventHashAlg("SHA-1");
        assertThat(AuditChainSupport.computeEventHash(null, mutated))
                .isNotEqualTo(baseline);
    }

    @Test
    void changedEventHashVersionChangesHash() {
        String baseline = AuditChainSupport.computeEventHash(null, sampleEvent());
        EndpointAuditEvent mutated = sampleEvent();
        mutated.setEventHashVersion(999);
        assertThat(AuditChainSupport.computeEventHash(null, mutated))
                .isNotEqualTo(baseline);
    }
}
