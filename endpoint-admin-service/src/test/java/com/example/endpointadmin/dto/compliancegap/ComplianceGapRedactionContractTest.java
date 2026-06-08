package com.example.endpointadmin.dto.compliancegap;

import com.example.endpointadmin.repository.ComplianceGapRepository;
import com.example.endpointadmin.service.EndpointComplianceGapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.7 COMPLETED-promotion gate (Codex {@code 019ea95d} finding 3 +
 * post-impl REVISE): machine-enforced redaction allowlist contract for the
 * compliance-gap response.
 *
 * <p>This binds the assertion to the REAL {@link EndpointComplianceGapService}
 * output (not a hand-built DTO fixture) — the service is driven with a mocked
 * {@link ComplianceGapRepository} returning a representative device row that
 * triggers BOTH MVP gap types, so the actual {@code GapDetail.details} /
 * {@code filterEcho} construction is what gets serialized and checked.
 *
 * <p>{@link GapDetail#details()} and {@link ComplianceGapResponse#filterEcho()}
 * are {@code Map<String,Object>}, so a future change could leak personal /
 * sensitive data onto the wire WITHOUT a compile error. The test asserts:
 * <ol>
 *   <li>each level's field names stay within an exact allowlist, and</li>
 *   <li>no field name anywhere in the tree matches a sensitive deny-list.</li>
 * </ol>
 */
class ComplianceGapRedactionContractTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant COLLECTED_AT = Instant.parse("2026-06-09T00:00:00Z");

    /** Exact field names permitted at each response level. */
    private static final Set<String> TOP_LEVEL =
            Set.of("items", "total", "page", "pageSize", "filterEcho", "computedAt");
    private static final Set<String> DEVICE_ITEM =
            Set.of("deviceId", "deviceName", "lastSeen", "gapCount", "gapStrength", "gaps", "staleComponents");
    private static final Set<String> GAP =
            Set.of("type", "label", "sourceSnapshotCollectedAt", "stale", "details");
    private static final Set<String> GAP_DETAILS =
            Set.of("rdpEnabled", "pendingTotalCount");
    private static final Set<String> FILTER_ECHO =
            Set.of("gapTypes", "freshnessWindow", "page", "pageSize");

    /**
     * Sensitive field names that must NEVER appear anywhere in the serialized
     * response (case-insensitive exact field-name match). Mirrors the source
     * redaction policy (AG-038-be SUMMARY_VALUE_DENYLIST_RE / NAME_FULLPATH).
     */
    private static final Set<String> DENY = Set.of(
            "ip", "ipaddress", "ip_address", "macaddress", "mac_address",
            "userupn", "user_upn", "upn", "username", "user_name",
            "sid", "usersid", "user_sid", "serial", "serialnumber", "serial_number",
            "password", "passwd", "token", "bearer", "secret", "apikey", "api_key",
            "cookie", "fullpath", "full_path", "path", "commandline", "command_line",
            "args", "arguments", "probeerrors", "probe_errors", "publisher",
            "downloadurl", "download_url", "url", "licensekey", "license_key",
            "rawpayload", "raw_payload", "payload", "email"
    );

    @Test
    void serviceOutputSerializesOnlyAllowlistedFields() throws Exception {
        // Real service output (both gap types) over a mocked repository row.
        ComplianceGapRepository repository = mock(ComplianceGapRepository.class);
        Object[] row = new Object[] {
                DEVICE_ID,          // r[0] device_id
                "HALILKOOLUB735",   // r[1] hostname
                null,               // r[2] display_name
                Boolean.TRUE,       // r[3] rdp_enabled  → RDP_ENABLED gap
                COLLECTED_AT,       // r[4] startup_collected_at
                Integer.valueOf(5), // r[5] pending_total_count > 0 → PENDING_SECURITY_UPDATES gap
                COLLECTED_AT        // r[6] hotfix_collected_at
        };
        when(repository.findGapDevices(any(), any(), anySet(), anyInt(), anyInt()))
                .thenReturn(java.util.Collections.singletonList(row));
        when(repository.countGapDevices(any(), any(), anySet())).thenReturn(1L);

        EndpointComplianceGapService service = new EndpointComplianceGapService(repository);
        ComplianceGapResponse response = service.findGaps(
                TENANT_ID, null, Duration.ofDays(7), 1, 50);

        String json = MAPPER.writeValueAsString(response);
        JsonNode root = MAPPER.readTree(json);

        // (a) structural allowlist — exact key set per level
        assertThat(fieldNames(root))
                .as("top-level response keys")
                .containsExactlyInAnyOrderElementsOf(TOP_LEVEL);

        JsonNode device = root.get("items").get(0);
        assertThat(fieldNames(device))
                .as("device-item keys")
                .containsExactlyInAnyOrderElementsOf(DEVICE_ITEM);

        assertThat(device.get("gaps")).as("both MVP gap types present").hasSize(2);
        for (JsonNode gap : device.get("gaps")) {
            assertThat(fieldNames(gap))
                    .as("gap keys")
                    .containsExactlyInAnyOrderElementsOf(GAP);
            assertThat(fieldNames(gap.get("details")))
                    .as("gap.details keys allowlist")
                    .allMatch(GAP_DETAILS::contains);
        }

        assertThat(fieldNames(root.get("filterEcho")))
                .as("filterEcho keys allowlist")
                .allMatch(FILTER_ECHO::contains);

        // (b) no sensitive field name anywhere in the tree
        Set<String> all = new TreeSet<>();
        collectFieldNames(root, all);
        Set<String> leaked = new TreeSet<>();
        for (String f : all) {
            if (DENY.contains(f.toLowerCase())) {
                leaked.add(f);
            }
        }
        assertThat(leaked)
                .as("compliance-gap response must never surface sensitive fields")
                .isEmpty();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> sink) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                sink.add(entry.getKey());
                collectFieldNames(entry.getValue(), sink);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldNames(child, sink);
            }
        }
    }
}
