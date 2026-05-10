package com.example.permission.service;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationActionPredicate;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.dto.v1.AuditExportJobResponseDto;
import com.example.permission.dto.v1.AuditEventIngestRequestDto;
import com.example.permission.model.AuditExportJob;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.model.UserAuditEventMirror;
import com.example.permission.repository.AuditExportJobRepository;
import com.example.permission.repository.PermissionAuditEventRepository;
import com.example.permission.repository.UserAuditEventMirrorRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_EXPORT_LIMIT = 2000;
    private static final int MAX_EXPORT_LIMIT = 10000;

    /**
     * PR-D2 iter-3 absorb (Codex 019e10bf P1): scope marker key embedded in
     * the persisted {@link AuditExportJob#getFilterSnapshot()} JSON. Read by
     * {@link #getCompletedExportJob(String, String, AuditReadScope)} to
     * re-validate the requested download scope against the job's create-time
     * scope. Pre-PR-D2 jobs do not have this marker and are fail-closed (404).
     *
     * <p>Underscore-prefixed key avoids collision with any
     * {@code filter[<name>]} key from the request (request keys never start
     * with underscore by spec).
     */
    private static final String SCOPE_MARKER_KEY = "__audit_read_scope";

    private final PermissionAuditEventRepository repository;
    private final AuditExportJobRepository auditExportJobRepository;
    private final UserAuditEventMirrorRepository userAuditEventMirrorRepository;
    private final ObjectMapper objectMapper;
    private final AuditEventStream auditEventStream;
    private final boolean liveStreamEnabled;

    public AuditEventService(PermissionAuditEventRepository repository,
                             AuditExportJobRepository auditExportJobRepository,
                             UserAuditEventMirrorRepository userAuditEventMirrorRepository,
                             ObjectMapper objectMapper,
                             AuditEventStream auditEventStream,
                             @Value("${audit.live-stream.enabled:true}") boolean liveStreamEnabled) {
        this.repository = repository;
        this.auditExportJobRepository = auditExportJobRepository;
        this.userAuditEventMirrorRepository = userAuditEventMirrorRepository;
        this.objectMapper = objectMapper;
        this.auditEventStream = auditEventStream;
        this.liveStreamEnabled = liveStreamEnabled;
    }

    /**
     * Backward-compatible default: GENERIC_AUDIT scope. Existing internal callers
     * (AuditCompareService etc.) get the safer behavior — generic feed never
     * leaks impersonation rows.
     */
    public AuditEventPageResponse listEvents(int page,
                                             int size,
                                             String sort,
                                             Map<String, String> filters) {
        return listEvents(page, size, sort, filters, AuditReadScope.GENERIC_AUDIT);
    }

    /**
     * Scope-aware list. PR-D2:
     * <ul>
     *   <li>{@link AuditReadScope#GENERIC_AUDIT} — IMPERSONATION_* rows excluded
     *       unconditionally (the {@code /api/audit/events} feed)</li>
     *   <li>{@link AuditReadScope#IMPERSONATION_AUDIT} — only IMPERSONATION_*
     *       rows; {@code filter[action]} validated against the canonical 5-set
     *       (or alias {@code "IMPERSONATION"}) by the controller</li>
     * </ul>
     */
    public AuditEventPageResponse listEvents(int page,
                                             int size,
                                             String sort,
                                             Map<String, String> filters,
                                             AuditReadScope scope) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<AuditEventResponse> events = filterAndSortEvents(filters, sort, scope);
        int startIndex = Math.min(safePage * safeSize, events.size());
        int endIndex = Math.min(startIndex + safeSize, events.size());
        return new AuditEventPageResponse(events.subList(startIndex, endIndex), safePage, events.size());
    }

    /** Backward-compatible default: GENERIC_AUDIT scope. */
    public List<AuditEventResponse> exportEvents(String sort,
                                                 Map<String, String> filters,
                                                 Integer limit) {
        return exportEvents(sort, filters, limit, AuditReadScope.GENERIC_AUDIT);
    }

    public List<AuditEventResponse> exportEvents(String sort,
                                                 Map<String, String> filters,
                                                 Integer limit,
                                                 AuditReadScope scope) {
        int pageSize = limit != null
                ? Math.min(Math.max(limit, 1), MAX_EXPORT_LIMIT)
                : DEFAULT_EXPORT_LIMIT;
        List<AuditEventResponse> events = filterAndSortEvents(filters, sort, scope);
        return events.subList(0, Math.min(pageSize, events.size()));
    }

    /** Backward-compatible default: GENERIC_AUDIT scope. */
    public AuditEventPageResponse findByIdPage(String idString) {
        return findByIdPage(idString, AuditReadScope.GENERIC_AUDIT);
    }

    /**
     * Scope-aware id shortcut. When loaded from
     * {@link AuditReadScope#GENERIC_AUDIT}, an impersonation row id returns
     * 404 — as if the row did not exist for this caller. This closes the
     * "id-shortcut leak" channel: an AUDIT viewer who guesses an impersonation
     * event id otherwise would receive the full record bypassing the
     * generic-feed exclusion filter.
     */
    public AuditEventPageResponse findByIdPage(String idString, AuditReadScope scope) {
        if (idString != null && idString.startsWith("user-")) {
            Long userAuditId = parseAuditId(idString.substring("user-".length()));
            return userAuditEventMirrorRepository.findById(userAuditId)
                    .map(this::mapUserAuditToResponse)
                    .filter(resp -> matchesScope(resp, scope))
                    .map(resp -> new AuditEventPageResponse(java.util.List.of(resp), 0, 1))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found"));
        }
        try {
            Long id = parseAuditId(idString);
            return repository.findById(id)
                    .map(this::mapToResponse)
                    .filter(resp -> matchesScope(resp, scope))
                    .map(resp -> new AuditEventPageResponse(java.util.List.of(resp), 0, 1))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found"));
        } catch (NumberFormatException nfe) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id format");
        }
    }

    public byte[] buildExportPayload(List<AuditEventResponse> events, String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return buildCsvPayload(events).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if ("json".equalsIgnoreCase(format)) {
            try {
                return objectMapper.writeValueAsBytes(events);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported export format: " + format);
    }

    /** Backward-compatible default: GENERIC_AUDIT scope. */
    public AuditExportJobResponseDto createExportJob(String requestedBy,
                                                     String format,
                                                     Integer limit,
                                                     String sort,
                                                     Map<String, String> filters) {
        return createExportJob(requestedBy, format, limit, sort, filters, AuditReadScope.GENERIC_AUDIT);
    }

    /**
     * Scope-aware export job creation. The job's payload is built with the
     * supplied scope so a GENERIC_AUDIT export never embeds impersonation
     * rows even if the caller crafts a clever filter string.
     */
    public AuditExportJobResponseDto createExportJob(String requestedBy,
                                                     String format,
                                                     Integer limit,
                                                     String sort,
                                                     Map<String, String> filters,
                                                     AuditReadScope scope) {
        String normalizedFormat = normalizeExportFormat(format);
        AuditExportJob job = new AuditExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setRequestedBy(requestedBy);
        job.setStatus("PROCESSING");
        job.setFormat(normalizedFormat);
        job.setSortValue(sort);
        // PR-D2 iter-3 absorb (Codex 019e10bf): persist the read-scope alongside
        // the user-supplied filters so download/get paths can re-validate scope
        // before returning the persisted payload. Pre-PR-D2 jobs lack this
        // marker — treated as legacy (404) by getCompletedExportJob.
        job.setFilterSnapshot(writeJsonSafe(buildFilterSnapshot(filters, scope)));
        job.setCreatedAt(Instant.now());
        auditExportJobRepository.save(job);

        try {
            List<AuditEventResponse> events = exportEvents(sort, filters == null ? Map.of() : filters, limit, scope);
            byte[] payload = buildExportPayload(events, normalizedFormat);
            job.setPayload(payload);
            job.setContentType(resolveExportContentType(normalizedFormat));
            job.setFilename("audit-events-%s.%s".formatted(job.getId(), normalizedFormat));
            job.setEventCount(events.size());
            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            auditExportJobRepository.save(job);
            recordEventSafely(buildExportJobAuditEvent(
                    "AUDIT_EXPORT_JOB_CREATED",
                    requestedBy,
                    "Audit export job completed with %s events".formatted(events.size()),
                    "INFO",
                    Map.of(
                            "jobId", job.getId(),
                            "format", normalizedFormat,
                            "eventCount", events.size(),
                            "status", job.getStatus()
                    )
            ));
        } catch (RuntimeException error) {
            job.setStatus("FAILED");
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(resolveExportErrorMessage(error));
            auditExportJobRepository.save(job);
            recordEventSafely(buildExportJobAuditEvent(
                    "AUDIT_EXPORT_JOB_FAILED",
                    requestedBy,
                    "Audit export job failed",
                    "ERROR",
                    Map.of(
                            "jobId", job.getId(),
                            "format", normalizedFormat,
                            "status", job.getStatus(),
                            "error", job.getErrorMessage()
                    )
            ));
        }

        return toExportJobResponse(job);
    }

    /** Backward-compatible default: GENERIC_AUDIT scope. */
    public AuditExportJobResponseDto getExportJob(String jobId, String requestedBy) {
        return getExportJob(jobId, requestedBy, AuditReadScope.GENERIC_AUDIT);
    }

    /**
     * Scope-aware status fetch. PR-D2 iter-3 absorb (Codex 019e10bf P1): a
     * GENERIC_AUDIT caller hitting an IMPERSONATION_AUDIT-scoped job (or vice
     * versa, or a legacy pre-PR-D2 job without a scope marker) gets 404 — as
     * if the job did not exist for this caller. Same pattern as
     * findByIdPage(...): fail-closed; never leak metadata across scope.
     */
    public AuditExportJobResponseDto getExportJob(String jobId,
                                                  String requestedBy,
                                                  AuditReadScope scope) {
        AuditExportJob job = findOwnedExportJob(jobId, requestedBy);
        requireMatchingScope(job, scope);
        return toExportJobResponse(job);
    }

    /** Backward-compatible default: GENERIC_AUDIT scope. */
    public AuditExportJob getCompletedExportJob(String jobId, String requestedBy) {
        return getCompletedExportJob(jobId, requestedBy, AuditReadScope.GENERIC_AUDIT);
    }

    /**
     * Scope-aware download. PR-D2 iter-3 absorb (Codex 019e10bf P1): the
     * persisted {@code job.payload} is treated as untrusted material and
     * re-validated against the requested scope before being returned.
     *
     * <p>Three layers of defense (any one is sufficient; together they are
     * defense in depth):
     * <ol>
     *   <li><b>Scope marker fail-closed</b> — the job's persisted scope marker
     *       (written at create time into the {@code filter_snapshot} JSON) must
     *       match the requested scope; missing marker = legacy / pre-PR-D2 job
     *       and is fail-closed to 404.</li>
     *   <li><b>Owner check</b> — pre-existing {@link #findOwnedExportJob}
     *       guards cross-user access.</li>
     *   <li><b>Payload re-filter (defensive)</b> — for GENERIC_AUDIT downloads
     *       on JSON payloads, the persisted bytes are re-deserialized and any
     *       IMPERSONATION_* rows are stripped before return. CSV payloads do
     *       not get this belt-and-braces re-filter (the scope-marker layer is
     *       already authoritative); a future ticket can add CSV parsing if
     *       business need arises.</li>
     * </ol>
     */
    public AuditExportJob getCompletedExportJob(String jobId,
                                                String requestedBy,
                                                AuditReadScope scope) {
        AuditExportJob job = findOwnedExportJob(jobId, requestedBy);
        requireMatchingScope(job, scope);
        if (!"COMPLETED".equalsIgnoreCase(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Export job is not ready");
        }
        if (job.getPayload() == null || job.getPayload().length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export payload not found");
        }
        return defensivelyFilterPayload(job, scope);
    }

    public SseEmitter openLiveStream() {
        if (!liveStreamEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Audit live stream disabled");
        }
        return auditEventStream.registerEmitter();
    }

    public PermissionAuditEvent recordEvent(PermissionAuditEvent event) {
        PermissionAuditEvent saved = repository.save(event);
        dispatchLiveEvent(saved);
        return saved;
    }

    public PermissionAuditEvent recordMirroredEvent(AuditEventIngestRequestDto request) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(request.getEventType());
        event.setPerformedBy(request.getPerformedBy());
        event.setDetails(request.getDetails());
        event.setUserEmail(request.getUserEmail());
        event.setService(request.getService());
        event.setLevel(request.getLevel());
        event.setAction(request.getAction());
        event.setCorrelationId(isNotBlank(request.getCorrelationId()) ? request.getCorrelationId() : UUID.randomUUID().toString());
        event.setMetadata(writeJsonSafe(request.getMetadata()));
        event.setBeforeState(writeJsonSafe(request.getBefore()));
        event.setAfterState(writeJsonSafe(request.getAfter()));
        event.setOccurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : Instant.now());
        return recordEvent(event);
    }

    public PermissionAuditEvent buildEvent(String eventType,
                                           Long performedBy,
                                           String details,
                                           Long targetUserId,
                                           String level,
                                           String action,
                                           Map<String, Object> metadata,
                                           Object beforeState,
                                           Object afterState) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(eventType);
        event.setPerformedBy(performedBy);
        event.setDetails(details);
        event.setUserEmail(targetUserId == null ? null : ("user:" + targetUserId));
        event.setService("permission-service");
        event.setLevel(level);
        event.setAction(action);
        event.setCorrelationId(java.util.UUID.randomUUID().toString());
        event.setMetadata(writeJsonSafe(metadata));
        event.setBeforeState(writeJsonSafe(beforeState));
        event.setAfterState(writeJsonSafe(afterState));
        event.setOccurredAt(java.time.Instant.now());
        return event;
    }

    private void dispatchLiveEvent(PermissionAuditEvent event) {
        if (!liveStreamEnabled || event == null) {
            return;
        }
        // PR-D2: AUDIT.can_view holders subscribed to /api/audit/events/live
        // must NOT receive IMPERSONATION_* events. The shared SSE channel
        // is gated by AUDIT.can_view, but impersonation events live behind
        // IMPERSONATION_AUDIT.can_view — at-source suppression is the
        // simplest correct closure (Codex iter-2 amendment: per-emitter
        // authz is deferred; a separate IMPERSONATION_AUDIT live stream
        // can be added in a follow-up if needed).
        if (ImpersonationActionPredicate.isImpersonationAction(event.getAction())) {
            return;
        }
        AuditEventResponse response = mapToResponse(event);
        auditEventStream.publish(response);
    }

    private void recordEventSafely(PermissionAuditEvent event) {
        try {
            recordEvent(event);
        } catch (RuntimeException error) {
            log.warn("Failed to persist audit export lifecycle event", error);
        }
    }

    private String buildCsvPayload(List<AuditEventResponse> events) {
        String header = String.join(",",
                "id", "timestamp", "userEmail", "service", "level", "action",
                "details", "correlationId", "metadata", "beforeState", "afterState");
        return events.stream()
                .map(this::toCsvRow)
                .collect(Collectors.joining("\n", header + "\n", ""));
    }

    private String toCsvRow(AuditEventResponse event) {
        return String.join(",",
                escapeCsv(event.id()),
                escapeCsv(event.timestamp() != null ? event.timestamp().toString() : ""),
                escapeCsv(event.userEmail()),
                escapeCsv(event.service()),
                escapeCsv(event.level()),
                escapeCsv(event.action()),
                escapeCsv(event.details()),
                escapeCsv(event.correlationId()),
                escapeCsv(writeJsonSafe(event.metadata())),
                escapeCsv(writeJsonSafe(event.before())),
                escapeCsv(writeJsonSafe(event.after()))
        );
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"%s\"".formatted(escaped);
    }

    private String writeJsonSafe(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialise audit payload", e);
            return value.toString();
        }
    }

    private List<AuditEventResponse> filterAndSortEvents(Map<String, String> filters,
                                                         String sort,
                                                         AuditReadScope scope) {
        return collectMergedEvents().stream()
                .filter(event -> matchesScope(event, scope))
                .filter(event -> matchesFilters(event, filters, scope))
                .sorted(resolveResponseComparator(sort))
                .toList();
    }

    /**
     * Scope predicate. Authoritative gate based on the canonical 5-set in
     * {@link ImpersonationActionPredicate} — no substring matching, no
     * accidental inclusion of {@code NON_IMPERSONATION_*} or fabricated codes.
     *
     * <ul>
     *   <li>{@link AuditReadScope#GENERIC_AUDIT}: returns true iff the action
     *       is NOT one of the 5 impersonation codes (or action is null).</li>
     *   <li>{@link AuditReadScope#IMPERSONATION_AUDIT}: returns true iff the
     *       action IS one of the 5 impersonation codes.</li>
     * </ul>
     */
    private boolean matchesScope(AuditEventResponse event, AuditReadScope scope) {
        boolean isImpersonation = ImpersonationActionPredicate.isImpersonationAction(event.action());
        return switch (scope) {
            case GENERIC_AUDIT -> !isImpersonation;
            case IMPERSONATION_AUDIT -> isImpersonation;
        };
    }

    private List<AuditEventResponse> collectMergedEvents() {
        List<AuditEventResponse> events = new ArrayList<>();
        repository.findAll().stream()
                .map(this::mapToResponse)
                .forEach(events::add);
        userAuditEventMirrorRepository.findAll().stream()
                .map(this::mapUserAuditToResponse)
                .forEach(events::add);
        return events;
    }

    private Comparator<AuditEventResponse> resolveResponseComparator(String sort) {
        Comparator<AuditEventResponse> comparator;
        if (sort == null || sort.isBlank()) {
            comparator = Comparator.comparing(AuditEventResponse::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
            return comparator.reversed();
        }
        String[] parts = sort.split(",");
        String field = parts[0];
        boolean ascending = parts.length > 1 && "asc".equalsIgnoreCase(parts[1]);
        comparator = switch (field) {
            case "userEmail" -> Comparator.comparing(AuditEventResponse::userEmail, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "service" -> Comparator.comparing(AuditEventResponse::service, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "level" -> Comparator.comparing(AuditEventResponse::level, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "action" -> Comparator.comparing(AuditEventResponse::action, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "correlationId" -> Comparator.comparing(AuditEventResponse::correlationId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(AuditEventResponse::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return ascending ? comparator : comparator.reversed();
    }

    private boolean matchesFilters(AuditEventResponse event, Map<String, String> filters, AuditReadScope scope) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (isNotBlank(filters.get("userEmail")) && !containsIgnoreCase(event.userEmail(), filters.get("userEmail"))) {
            return false;
        }
        if (isNotBlank(filters.get("service")) && !containsIgnoreCase(event.service(), filters.get("service"))) {
            return false;
        }
        if (isNotBlank(filters.get("level"))
                && (event.level() == null || !event.level().equalsIgnoreCase(filters.get("level")))) {
            return false;
        }
        String actionFilter = filters.get("action");
        if (isNotBlank(actionFilter)) {
            if (scope == AuditReadScope.IMPERSONATION_AUDIT) {
                // Strict: the controller has already validated the value
                // against ImpersonationActionPredicate.isAllowedImpersonationFilter.
                // ALIAS_ALL ("IMPERSONATION") = no narrowing (all 5 pass);
                // a specific impersonation code = exact match only.
                if (!ImpersonationActionPredicate.ALIAS_ALL.equals(actionFilter)
                        && !actionFilter.equals(event.action())) {
                    return false;
                }
            } else if (!containsIgnoreCase(event.action(), actionFilter)) {
                return false;
            }
        }
        if (isNotBlank(filters.get("correlationId"))
                && (event.correlationId() == null || !event.correlationId().equals(filters.get("correlationId")))) {
            return false;
        }
        Instant dateFrom = parseInstant(filters.get("dateFrom"));
        if (dateFrom != null && (event.timestamp() == null || event.timestamp().isBefore(dateFrom))) {
            return false;
        }
        Instant dateTo = parseInstant(filters.get("dateTo"));
        if (dateTo != null && (event.timestamp() == null || event.timestamp().isAfter(dateTo))) {
            return false;
        }
        String search = filters.get("search");
        if (isNotBlank(search)) {
            return containsIgnoreCase(event.details(), search)
                    || containsIgnoreCase(event.action(), search)
                    || containsIgnoreCase(event.userEmail(), search)
                    || containsIgnoreCase(event.service(), search)
                    || containsIgnoreCase(event.correlationId(), search);
        }
        return true;
    }

    private Instant parseInstant(String value) {
        if (!isNotBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception error) {
            log.debug("Failed to parse audit date filter: {}", value, error);
            return null;
        }
    }

    private boolean containsIgnoreCase(String source, String needle) {
        if (!isNotBlank(source) || !isNotBlank(needle)) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Long parseAuditId(String idString) {
        return Long.valueOf(idString);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeExportFormat(String format) {
        return "csv".equalsIgnoreCase(format) ? "csv" : "json";
    }

    private String resolveExportContentType(String format) {
        return "csv".equalsIgnoreCase(format) ? "text/csv" : "application/json";
    }

    private String resolveExportErrorMessage(RuntimeException error) {
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return "Audit export job failed";
    }

    private AuditExportJob findOwnedExportJob(String jobId, String requestedBy) {
        AuditExportJob job = auditExportJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit export job not found"));
        String jobOwner = job.getRequestedBy() == null ? "" : job.getRequestedBy();
        String currentUser = requestedBy == null ? "" : requestedBy;
        if (!jobOwner.equalsIgnoreCase(currentUser)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit export job not found");
        }
        return job;
    }

    /**
     * PR-D2 iter-3 absorb (Codex 019e10bf P1): builds the create-time
     * persisted snapshot of {@code filter[*]} request keys plus an internal
     * scope marker. The scope marker key never collides with a user-supplied
     * filter (underscore-prefixed by construction) and is consumed by
     * {@link #readPersistedScope(AuditExportJob)} on download.
     */
    private Map<String, String> buildFilterSnapshot(Map<String, String> filters, AuditReadScope scope) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (filters != null) {
            // Defensive: drop any caller-injected scope marker so they cannot
            // forge a different scope onto a job at create time.
            filters.forEach((key, value) -> {
                if (!SCOPE_MARKER_KEY.equals(key)) {
                    snapshot.put(key, value);
                }
            });
        }
        snapshot.put(SCOPE_MARKER_KEY, scope.name());
        return snapshot;
    }

    /**
     * Reads the persisted scope marker from a job's {@code filterSnapshot}.
     * Returns {@code null} if the snapshot is missing, malformed, or has no
     * marker (legacy pre-PR-D2 jobs). Callers MUST treat null as "scope
     * unknown" and fail-closed (Codex 019e10bf P1).
     */
    private AuditReadScope readPersistedScope(AuditExportJob job) {
        String snapshot = job.getFilterSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(snapshot, new TypeReference<>() { });
            Object marker = parsed.get(SCOPE_MARKER_KEY);
            if (marker == null) {
                return null;
            }
            return AuditReadScope.valueOf(marker.toString());
        } catch (JsonProcessingException | RuntimeException error) {
            // Covers ObjectMapper parse failures (JsonProcessingException is
            // checked) plus IllegalArgumentException (unknown enum value via
            // valueOf) plus any other runtime parse failure. Treat all as
            // "scope unknown" → fail-closed by the caller.
            log.debug("Failed to parse scope marker on export job {}", job.getId(), error);
            return null;
        }
    }

    /**
     * PR-D2 iter-3 absorb (Codex 019e10bf P1): fail-closed scope guard for
     * export-job get/download. A job without a persisted scope marker is
     * legacy (pre-PR-D2) and is treated as "out of scope" for any caller —
     * 404 is the safest closure (the legacy payload may contain
     * IMPERSONATION_* rows from before the exclusion was enforced).
     *
     * <p>This is the same pattern as {@link #findByIdPage(String, AuditReadScope)}:
     * the resource exists in the DB but is invisible to this caller.
     */
    private void requireMatchingScope(AuditExportJob job, AuditReadScope requestedScope) {
        AuditReadScope persistedScope = readPersistedScope(job);
        if (persistedScope == null || persistedScope != requestedScope) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit export job not found");
        }
    }

    /**
     * PR-D2 iter-3 absorb (Codex 019e10bf P1): defense-in-depth payload
     * re-filter for GENERIC_AUDIT JSON downloads. The scope-marker check above
     * is already authoritative, but this second pass guarantees that even if
     * a future bug, manual SQL edit, or restored backup somehow placed an
     * IMPERSONATION_* row inside a GENERIC_AUDIT-marked job, the row never
     * leaves the service.
     *
     * <p>Returns the same job instance with an in-place updated payload; the
     * job is detached from the persistence context here (no dirty checking
     * triggers a write — the entity returned to the controller is read-only
     * from this point onward).
     */
    private AuditExportJob defensivelyFilterPayload(AuditExportJob job, AuditReadScope scope) {
        if (scope != AuditReadScope.GENERIC_AUDIT) {
            return job;
        }
        if (!"json".equalsIgnoreCase(job.getFormat())) {
            // CSV re-parse is intentionally out of scope for iter-3; the
            // scope-marker layer remains authoritative for non-JSON formats.
            return job;
        }
        byte[] payload = job.getPayload();
        if (payload == null || payload.length == 0) {
            return job;
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    payload, new TypeReference<>() { });
            List<Map<String, Object>> filtered = new ArrayList<>(rows.size());
            boolean changed = false;
            for (Map<String, Object> row : rows) {
                Object action = row.get("action");
                if (action != null && ImpersonationActionPredicate.isImpersonationAction(action.toString())) {
                    changed = true;
                    continue;
                }
                filtered.add(row);
            }
            if (changed) {
                byte[] rewritten = objectMapper.writeValueAsBytes(filtered);
                job.setPayload(rewritten);
                log.warn("Defensively stripped {} impersonation row(s) from export job {} payload at download time",
                        rows.size() - filtered.size(), job.getId());
            }
        } catch (RuntimeException | java.io.IOException error) {
            log.debug("Failed to defensively re-filter export job {} payload; "
                    + "scope-marker check already passed so returning persisted bytes",
                    job.getId(), error);
        }
        return job;
    }

    private AuditExportJobResponseDto toExportJobResponse(AuditExportJob job) {
        return new AuditExportJobResponseDto(
                job.getId(),
                job.getStatus(),
                job.getFormat(),
                job.getFilename(),
                job.getContentType(),
                job.getEventCount(),
                job.getRequestedBy(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                "/api/audit/events/export-jobs/%s/download".formatted(job.getId())
        );
    }

    private PermissionAuditEvent buildExportJobAuditEvent(String eventType,
                                                          String requestedBy,
                                                          String details,
                                                          String level,
                                                          Map<String, Object> metadata) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(eventType);
        event.setPerformedBy(null);
        event.setDetails(details);
        event.setUserEmail(requestedBy);
        event.setService("permission-service");
        event.setLevel(level);
        event.setAction(eventType);
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setMetadata(writeJsonSafe(metadata));
        event.setBeforeState(writeJsonSafe(Map.of()));
        event.setAfterState(writeJsonSafe(Map.of()));
        event.setOccurredAt(Instant.now());
        return event;
    }

    private AuditEventResponse mapToResponse(PermissionAuditEvent event) {
        Map<String, Object> metadata = redactPayload(parseJson(event.getMetadata()));
        Map<String, Object> before = redactPayload(parseJson(event.getBeforeState()));
        Map<String, Object> after = redactPayload(parseJson(event.getAfterState()));

        return new AuditEventResponse(
                event.getId() != null ? event.getId().toString() : null,
                event.getOccurredAt(),
                maskUserIdentifier(event.getUserEmail()),
                event.getService(),
                event.getLevel(),
                event.getAction(),
                event.getDetails(),
                event.getCorrelationId(),
                metadata,
                before,
                after
        );
    }

    private AuditEventResponse mapUserAuditToResponse(UserAuditEventMirror event) {
        Instant occurredAt = event.getOccurredAt() == null
                ? Instant.now()
                : event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventType", event.getEventType());
        metadata.put("performedBy", event.getPerformedBy());
        metadata.put("targetUserId", event.getTargetUserId());
        return new AuditEventResponse(
                "user-" + event.getId(),
                occurredAt,
                null,
                "user-service",
                resolveUserAuditLevel(event.getEventType()),
                resolveUserAuditAction(event.getEventType()),
                event.getDetails(),
                "user-audit-" + event.getId(),
                redactPayload(metadata),
                Map.of(),
                Map.of()
        );
    }

    private String resolveUserAuditLevel(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "INFO";
        }
        return eventType.endsWith("FAILED") ? "ERROR" : "INFO";
    }

    private String resolveUserAuditAction(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "USER_EVENT";
        }
        return eventType.toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> parseJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            log.debug("Failed to parse audit JSON payload", e);
            return Map.of("raw", value);
        }
    }

    private Map<String, Object> redactPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        payload.forEach((key, value) -> sanitized.put(key, redactValue(key, value)));
        return sanitized;
    }

    private Object redactValue(String key, Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            mapValue.forEach((nestedKey, nestedValue) ->
                    nested.put(String.valueOf(nestedKey), redactValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            for (Object element : collection) {
                sanitized.add(redactValue(key, element));
            }
            return sanitized;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(redactValue(key, java.lang.reflect.Array.get(value, i)));
            }
            return sanitized;
        }
        if (value instanceof String stringValue && shouldMask(key, stringValue)) {
            return maskString(key, stringValue);
        }
        return value;
    }

    private String maskUserIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return maskEmail(value);
    }

    private boolean shouldMask(String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (EXACT_SENSITIVE_KEYS.contains(normalizedKey)) {
            return true;
        }
        for (String part : PARTIAL_SENSITIVE_KEYS) {
            if (!part.isBlank() && normalizedKey.contains(part)) {
                return true;
            }
        }
        return value.contains("@");
    }

    private String maskString(String key, String value) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (value.contains("@") || normalizedKey.contains("email")) {
            return maskEmail(value);
        }
        if (value.length() <= 2) {
            return "*".repeat(value.length());
        }
        return value.charAt(0) + "***";
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return maskGeneric(email);
        }
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (localPart.length() <= 1) {
            return "*" + domain;
        }
        return localPart.charAt(0) + "***" + domain;
    }

    private String maskGeneric(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= 2) {
            return "*".repeat(value.length());
        }
        return value.charAt(0) + "***";
    }

    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "name",
            "fullname",
            "firstName",
            "firstname",
            "lastName",
            "lastname",
            "displayname",
            "contactname",
            "phone",
            "phonenumber",
            "mobile",
            "address",
            "ip",
            "identifier",
            "nationalid",
            "citizenid",
            "ssn"
    );

    private static final Set<String> PARTIAL_SENSITIVE_KEYS = Set.of(
            "email",
            "phone",
            "address",
            "token",
            "secret",
            "identifier",
            "ip"
    );
}
