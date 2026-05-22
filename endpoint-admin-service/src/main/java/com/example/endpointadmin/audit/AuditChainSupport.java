package com.example.endpointadmin.audit;

import com.example.endpointadmin.model.EndpointAuditEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * BE-016 — Audit integrity hash-chain support (Codex 019e4f8e plan-time AGREE).
 *
 * <p>Produces a deterministic canonical payload for an {@link EndpointAuditEvent}
 * and computes the chained {@code event_hash}.
 *
 * <h2>Canonical payload — fixed field set</h2>
 * The hash covers exactly: {@code id, tenant_id, device_id, command_id,
 * event_type, action, performed_by_subject, correlation_id, metadata,
 * before_state, after_state, occurred_at, event_hash_alg, event_hash_version}.
 *
 * <p>The hash columns themselves ({@code event_hash}, {@code prev_event_hash})
 * are NEVER part of the canonical payload — chain linkage is applied as an
 * outer composition so "payload canonical form" and "chain composition" stay
 * independent (Codex 019e4f8e).
 *
 * <h2>Determinism guarantees</h2>
 * <ul>
 *   <li>JSON object keys sorted ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}
 *       + alphabetic field order on the root object).</li>
 *   <li>{@link Instant} normalized to microsecond precision (Postgres
 *       {@code timestamp(6)}) then {@code ISO_INSTANT} formatted, so a row
 *       re-read from Postgres hashes identically to the in-memory pre-insert
 *       value.</li>
 *   <li>{@link UUID} lowercase canonical string.</li>
 *   <li>{@code null} fields emitted as JSON {@code null} (stable null policy).</li>
 * </ul>
 *
 * <h2>Chain composition</h2>
 * {@code event_hash = SHA-256( DOMAIN_PREFIX \n "prev=" <prev|GENESIS> \n
 * "payload=" <canonical-json> )} — domain-separated + versioned so a future
 * canonicalization change can bump {@link #HASH_VERSION} without colliding
 * with v1 hashes.
 */
public final class AuditChainSupport {

    /** Hash algorithm label persisted to {@code event_hash_alg}. */
    public static final String HASH_ALGORITHM = "SHA-256";

    /** Canonicalization scheme version persisted to {@code event_hash_version}. */
    public static final int HASH_VERSION = 1;

    /** Domain-separation prefix — prevents cross-context hash reuse. */
    public static final String DOMAIN_PREFIX = "endpoint-admin-audit:v1";

    /** Sentinel for the per-tenant GENESIS row (no previous hash). */
    public static final String GENESIS = "GENESIS";

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Object mapper dedicated to canonicalization — map entries sorted by key
     * so {@code metadata}/{@code before_state}/{@code after_state} JSON is
     * deterministic regardless of insertion order.
     */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private AuditChainSupport() {
    }

    /**
     * Build the canonical JSON payload string for an audit event. The event's
     * {@code occurredAt} MUST already be set (the caller normalizes it before
     * hashing — see {@link #normalizeTimestamp(Instant)}).
     */
    public static String canonicalPayload(EndpointAuditEvent event) {
        ObjectNode root = CANONICAL_MAPPER.createObjectNode();
        // Alphabetic field order on the root object for determinism.
        root.put("action", event.getAction());
        putJson(root, "after_state", event.getAfterState());
        putJson(root, "before_state", event.getBeforeState());
        root.put("command_id", uuidOrNull(event.getCommand() == null ? null : event.getCommand().getId()));
        root.put("correlation_id", event.getCorrelationId());
        root.put("device_id", uuidOrNull(event.getDevice() == null ? null : event.getDevice().getId()));
        // BE-016 (Codex 019e4f8e P2-3): hash the STORED alg/version values, not
        // the current constants — so a tamper of these columns is detectable
        // and versioned verification stays sound. record() sets them before
        // computeEventHash() is called, so they are populated here.
        root.put("event_hash_alg", event.getEventHashAlg());
        root.put("event_hash_version", event.getEventHashVersion());
        root.put("event_type", event.getEventType());
        root.put("id", uuidOrNull(event.getId()));
        putJson(root, "metadata", event.getMetadata());
        root.put("occurred_at", event.getOccurredAt() == null ? null
                : event.getOccurredAt().toString());
        root.put("performed_by_subject", event.getPerformedBySubject());
        root.put("tenant_id", uuidOrNull(event.getTenantId()));
        try {
            return CANONICAL_MAPPER.writer()
                    .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Audit canonical payload serialization failed", ex);
        }
    }

    /**
     * Compute the chained {@code event_hash} for an event given the previous
     * row's hash ({@code null} = tenant GENESIS).
     */
    public static String computeEventHash(String prevEventHash, EndpointAuditEvent event) {
        String prev = (prevEventHash == null || prevEventHash.isBlank()) ? GENESIS : prevEventHash;
        String composed = DOMAIN_PREFIX + "\n"
                + "prev=" + prev + "\n"
                + "payload=" + canonicalPayload(event);
        return sha256Hex(composed);
    }

    /**
     * Normalize an instant to microsecond precision so a value re-read from a
     * Postgres {@code timestamp(6)} column hashes identically to its
     * pre-insert form.
     */
    public static Instant normalizeTimestamp(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static void putJson(ObjectNode root, String field, Map<String, Object> value) {
        if (value == null) {
            root.putNull(field);
            return;
        }
        JsonNode node = CANONICAL_MAPPER.valueToTree(value);
        root.set(field, node);
    }

    private static String uuidOrNull(UUID uuid) {
        return uuid == null ? null : uuid.toString().toLowerCase();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
