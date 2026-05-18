package com.example.user.controller;

import com.example.user.dto.KeycloakUserProvisionRequest;
import com.example.user.dto.UpdateUserRequest;
import com.example.user.dto.v1.PagedUserResponseDto;
import com.example.user.dto.v1.UpdateUserDateFormatRequestDto;
import com.example.user.dto.v1.UpdateUserLocaleRequestDto;
import com.example.user.dto.v1.UpdateUserSessionTimeoutRequestDto;
import com.example.user.dto.v1.UpdateUserTimeFormatRequestDto;
import com.example.user.dto.v1.UpdateUserTimezoneRequestDto;
import com.example.user.dto.v1.UserActivationRequestDto;
import com.example.user.dto.v1.UserDetailDto;
import com.example.user.dto.v1.UserDateFormatMutationDto;
import com.example.user.dto.v1.UserDateFormatSnapshotDto;
import com.example.user.dto.v1.UserDtoMapper;
import com.example.user.dto.v1.UserLocaleMutationDto;
import com.example.user.dto.v1.UserLocaleSnapshotDto;
import com.example.user.dto.v1.UserSessionTimeoutMutationDto;
import com.example.user.dto.v1.UserSessionTimeoutSnapshotDto;
import com.example.user.dto.v1.UserSummaryDto;
import com.example.user.dto.v1.UserTimeFormatMutationDto;
import com.example.user.dto.v1.UserTimeFormatSnapshotDto;
import com.example.user.dto.v1.UserMutationAckDto;
import com.example.user.dto.v1.UserTimezoneMutationDto;
import com.example.user.dto.v1.UserTimezoneSnapshotDto;
import com.example.commonauth.AuthorizationContext;
import com.example.user.authz.AuthorizationContextService;
import com.example.user.model.User;
import com.example.user.permission.PermissionActions;
import com.example.user.service.CurrentUserResolver;
import com.example.user.service.UserAuditEventService;
import com.example.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.user.security.ServiceAuthenticationToken;

@RestController
@RequestMapping("/api/v1/users")
// STORY-0032: User REST/DTO v1 Migration
public class UserControllerV1 {

    private final UserService userService;
    private final UserAuditEventService userAuditEventService;
    private final AuthorizationContextService authorizationContextService;
    private final CurrentUserResolver currentUserResolver;
    private final MeterRegistry meterRegistry;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(UserControllerV1.class);

    public UserControllerV1(UserService userService,
                            UserAuditEventService userAuditEventService,
                            AuthorizationContextService authorizationContextService,
                            CurrentUserResolver currentUserResolver,
                            MeterRegistry meterRegistry) {
        this.userService = userService;
        this.userAuditEventService = userAuditEventService;
        this.authorizationContextService = authorizationContextService;
        this.currentUserResolver = currentUserResolver;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping
    public ResponseEntity<PagedUserResponseDto> listUsers(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                                          @RequestParam(value = "search", required = false) String search,
                                                          @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
                                                          @RequestParam(value = "role", required = false, defaultValue = "ALL") String role,
                                                          @RequestParam(value = "sort", required = false) String sort,
                                                          @RequestParam(value = "advancedFilter", required = false) String advancedFilter,
                                                          @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                          @RequestParam(value = "pageSize", required = false, defaultValue = "25") int pageSize,
                                                          @RequestParam(value = "dataSource", required = false) String dataSource,
                                                          @RequestParam(value = "rowGroupCols", required = false) String rowGroupCols,
                                                          @RequestParam(value = "groupKeys", required = false) String groupKeys,
                                                          @RequestParam(value = "valueCols", required = false) String valueCols,
                                                          @RequestParam(value = "pivotMode", required = false, defaultValue = "false") boolean pivotMode,
                                                          @RequestParam(value = "pivotCols", required = false) String pivotCols,
                                                          @RequestParam(value = "multiSearch", required = false) String multiSearch) {
        User currentUser = requireCurrentUser();
        Timer.Sample sample = Timer.start(meterRegistry);
        String resultTag = "success";
        boolean hasSearchParam = StringUtils.hasText(search);
        boolean hasAdvancedFilterParam = StringUtils.hasText(advancedFilter);
        boolean hasSortParam = StringUtils.hasText(sort);

        requirePermissionWithCompanyScope(PermissionActions.USER_READ, companyId);

        Sort parsedSort = parseSort(sort);
        org.springframework.data.jpa.domain.Specification<User> extraSpec = buildAdvancedFilterSpecSafe(advancedFilter);

        // multiSearch: pipe-separated values → OR across name + email
        if (StringUtils.hasText(multiSearch)) {
            String[] terms = multiSearch.split("\\|");
            org.springframework.data.jpa.domain.Specification<User> multiSpec = null;
            for (String term : terms) {
                final String trimmed = term.trim();
                if (trimmed.isEmpty()) continue;
                final String like = "%" + trimmed.toLowerCase() + "%";
                org.springframework.data.jpa.domain.Specification<User> termSpec =
                        (root, query, cb) -> cb.or(
                                cb.like(cb.lower(root.get("name")), like),
                                cb.like(cb.lower(root.get("email")), like)
                        );
                multiSpec = multiSpec == null ? termSpec : multiSpec.or(termSpec);
            }
            if (multiSpec != null) {
                extraSpec = extraSpec == null ? multiSpec : extraSpec.and(multiSpec);
            }
        }

        boolean clientMode = dataSource != null && dataSource.equalsIgnoreCase("client");
        boolean unpaged = clientMode || pageSize <= 0;
        String modeTag = unpaged ? "client" : "server";

        // ── Server-side grouping ──
        String[] groupColArray = StringUtils.hasText(rowGroupCols) ? rowGroupCols.split(",") : new String[0];
        String[] groupKeyArray = StringUtils.hasText(groupKeys) ? groupKeys.split(",") : new String[0];
        boolean isGroupRequest = groupColArray.length > 0 && groupKeyArray.length < groupColArray.length;

        try {
            // ── Pivot mode ──
            if (pivotMode && StringUtils.hasText(pivotCols) && groupColArray.length > 0) {
                String groupField = groupColArray[0];
                org.springframework.data.jpa.domain.Specification<User> pivotSpec = extraSpec;
                java.util.Map<String, Object> pivotResult = userService.computePivot(pivotSpec, groupField, pivotCols, valueCols);
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, Object>> pivotItems = (List<java.util.Map<String, Object>>) pivotResult.get("items");
                @SuppressWarnings("unchecked")
                List<String> secondaryCols = (List<String>) pivotResult.get("secondaryColumns");

                // Convert pivot maps to UserSummaryDto (name = group value)
                List<UserSummaryDto> items = pivotItems.stream().map(m -> {
                    UserSummaryDto dto = new UserSummaryDto();
                    dto.setName(String.valueOf(m.get(groupField)));
                    dto.setRole(groupField.equals("role") ? String.valueOf(m.get(groupField)) : null);
                    return dto;
                }).collect(Collectors.toList());

                PagedUserResponseDto response = new PagedUserResponseDto(items, items.size(), 1, items.size());
                response.setSecondaryColumns(secondaryCols);
                // Include raw pivot data as aggData for frontend to read column values
                java.util.Map<String, Object> aggMap = new java.util.LinkedHashMap<>();
                aggMap.put("pivotRows", pivotItems);
                response.setAggData(aggMap);
                return ResponseEntity.ok(response);
            }

            if (isGroupRequest) {
                // Return distinct values with count + aggregation for current group level
                String groupField = groupColArray[groupKeyArray.length];
                // Apply parent group filters
                org.springframework.data.jpa.domain.Specification<User> groupSpec = extraSpec;
                for (int i = 0; i < groupKeyArray.length; i++) {
                    final String col = groupColArray[i];
                    final String key = groupKeyArray[i];
                    groupSpec = groupSpec == null
                            ? (root, query, cb) -> cb.equal(root.get(col), key)
                            : groupSpec.and((root, query, cb) -> cb.equal(root.get(col), key));
                }
                // Parse valueCols to know field names for mapping agg results
                String[] valueColParts = (valueCols != null && !valueCols.isBlank())
                        ? valueCols.split(",") : new String[0];
                List<Object[]> groups = userService.getDistinctGroupValues(search, status, role, groupSpec, groupField, parsedSort, valueCols);
                List<UserSummaryDto> groupRows = groups.stream().map(row -> {
                    UserSummaryDto dto = new UserSummaryDto();
                    String groupValue = String.valueOf(row[0]);
                    long childCount = row.length > 1 ? ((Number) row[1]).longValue() : 0;
                    dto.setName(groupValue + " (" + childCount + ")");
                    dto.setEmail(null);
                    dto.setRole(groupField.equals("role") ? groupValue : null);
                    // Map aggregate values from row[2..] to dto fields
                    for (int ai = 0; ai < valueColParts.length && (ai + 2) < row.length; ai++) {
                        String[] vp = valueColParts[ai].split(":");
                        String field = vp[0].trim();
                        Object val = row[ai + 2];
                        // Write to known DTO fields
                        if ("sessionTimeoutMinutes".equals(field) && val instanceof Number) {
                            dto.setSessionTimeoutMinutes(((Number) val).intValue());
                        }
                    }
                    return dto;
                }).collect(Collectors.toList());

                // Compute overall aggregation for the group level
                PagedUserResponseDto response = new PagedUserResponseDto(groupRows, groupRows.size(), 1, groupRows.size());
                if (StringUtils.hasText(valueCols)) {
                    java.util.Map<String, Object> aggData = userService.computeAggregation(groupSpec, valueCols);
                    response.setAggData(aggData);
                }
                return ResponseEntity.ok(response);
            }

            // ── Apply group key filters for leaf-level requests ──
            org.springframework.data.jpa.domain.Specification<User> leafSpec = extraSpec;
            for (int i = 0; i < Math.min(groupColArray.length, groupKeyArray.length); i++) {
                final String col = groupColArray[i];
                final String key = groupKeyArray[i];
                leafSpec = leafSpec == null
                        ? (root, query, cb) -> cb.equal(root.get(col), key)
                        : leafSpec.and((root, query, cb) -> cb.equal(root.get(col), key));
            }
            extraSpec = leafSpec;

            if (unpaged) {
                List<UserSummaryDto> items = userService.searchUsers(search, status, role, extraSpec, parsedSort).stream()
                        .map(UserDtoMapper::toSummary)
                        .collect(Collectors.toList());
                PagedUserResponseDto response = new PagedUserResponseDto(items, items.size(), 1, items.size());
                return ResponseEntity.ok(response);
            }

            int safePage = Math.max(page, 1) - 1;
            int safePageSize = Math.max(pageSize, 1);
            Pageable pageable = PageRequest.of(safePage, safePageSize, parsedSort);

            Page<User> result = userService.searchUsers(search, status, role, extraSpec, pageable);

            List<UserSummaryDto> items = result.getContent().stream()
                    .map(UserDtoMapper::toSummary)
                    .collect(Collectors.toList());

            PagedUserResponseDto response = new PagedUserResponseDto(
                    items,
                    result.getTotalElements(),
                    result.getNumber() + 1,
                    result.getSize());

            return ResponseEntity.ok(response);
        } catch (ResponseStatusException ex) {
            resultTag = "error";
            throw ex;
        } finally {
            recordSearchMetrics(sample, modeTag, hasSearchParam, hasAdvancedFilterParam, hasSortParam, resultTag);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDetailDto> getUser(@PathVariable Long id) {
        User user = userService.findRequiredById(id);
        return ResponseEntity.ok(UserDtoMapper.toDetail(user));
    }

    @GetMapping("/by-email")
    public ResponseEntity<UserDetailDto> getUserByEmail(@RequestParam("email") String email) {
        return userService.findByEmail(email)
                .map(UserDtoMapper::toDetail)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/activation")
    public ResponseEntity<UserMutationAckDto> updateActivation(@PathVariable Long id,
                                                               @Valid @RequestBody UserActivationRequestDto request) {
        User currentUser = requireCurrentUser();
        String auditId = userService.updateActivation(id, Boolean.TRUE.equals(request.getActive()), currentUser.getId());
        return ResponseEntity.ok(UserMutationAckDto.ok(prefixUserAuditId(auditId)));
    }

    @GetMapping("/me/session-timeout")
    public ResponseEntity<UserSessionTimeoutSnapshotDto> getCurrentSessionTimeoutSnapshot() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(new UserSessionTimeoutSnapshotDto(
                buildProfileResourceKey(currentUser),
                currentUser.getSessionTimeoutMinutes(),
                safeVersion(currentUser)
        ));
    }

    @GetMapping("/me/profile")
    public ResponseEntity<UserDetailDto> getCurrentUserProfile() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(UserDtoMapper.toDetail(currentUser));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<UserDetailDto> updateCurrentUserProfile(@Valid @RequestBody UpdateUserRequest request) {
        User currentUser = requireCurrentUser();
        UpdateUserRequest selfUpdateRequest = new UpdateUserRequest();
        selfUpdateRequest.setName(request.getName());
        UserAuditSnapshot existingUser = UserAuditSnapshot.from(currentUser);
        User updatedUser = userService.updateUser(currentUser.getId(), selfUpdateRequest);
        recordUserUpdateAudit(currentUser, existingUser, updatedUser, selfUpdateRequest);
        return ResponseEntity.ok(UserDtoMapper.toDetail(updatedUser));
    }

    @PutMapping("/me/session-timeout")
    public ResponseEntity<UserSessionTimeoutMutationDto> updateCurrentSessionTimeout(@Valid @RequestBody UpdateUserSessionTimeoutRequestDto request) {
        User currentUser = requireCurrentUser();
        var result = userService.syncOwnSessionTimeout(
                currentUser.getId(),
                request.getSessionTimeoutMinutes(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );
        UserSessionTimeoutMutationDto response = result.isConflict()
                ? UserSessionTimeoutMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.sessionTimeoutMinutes(),
                        result.version(),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : UserSessionTimeoutMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.sessionTimeoutMinutes(),
                        result.version(),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    @GetMapping("/me/locale")
    public ResponseEntity<UserLocaleSnapshotDto> getCurrentLocaleSnapshot() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(new UserLocaleSnapshotDto(
                buildProfileResourceKey(currentUser),
                currentUser.getLocale(),
                safeVersion(currentUser)
        ));
    }

    @PutMapping("/me/locale")
    public ResponseEntity<UserLocaleMutationDto> updateCurrentLocale(@Valid @RequestBody UpdateUserLocaleRequestDto request) {
        User currentUser = requireCurrentUser();
        var result = userService.syncOwnLocale(
                currentUser.getId(),
                request.getLocale(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );
        UserLocaleMutationDto response = result.isConflict()
                ? UserLocaleMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.locale(),
                        result.version(),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : UserLocaleMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.locale(),
                        result.version(),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    @GetMapping("/me/timezone")
    public ResponseEntity<UserTimezoneSnapshotDto> getCurrentTimezoneSnapshot() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(new UserTimezoneSnapshotDto(
                buildProfileResourceKey(currentUser),
                currentUser.getTimezone(),
                safeVersion(currentUser)
        ));
    }

    @PutMapping("/me/timezone")
    public ResponseEntity<UserTimezoneMutationDto> updateCurrentTimezone(@Valid @RequestBody UpdateUserTimezoneRequestDto request) {
        User currentUser = requireCurrentUser();
        var result = userService.syncOwnTimezone(
                currentUser.getId(),
                request.getTimezone(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );
        UserTimezoneMutationDto response = result.isConflict()
                ? UserTimezoneMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.timezone(),
                        result.version(),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : UserTimezoneMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.timezone(),
                        result.version(),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    @GetMapping("/me/date-format")
    public ResponseEntity<UserDateFormatSnapshotDto> getCurrentDateFormatSnapshot() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(new UserDateFormatSnapshotDto(
                buildProfileResourceKey(currentUser),
                currentUser.getDateFormat(),
                safeVersion(currentUser)
        ));
    }

    @PutMapping("/me/date-format")
    public ResponseEntity<UserDateFormatMutationDto> updateCurrentDateFormat(@Valid @RequestBody UpdateUserDateFormatRequestDto request) {
        User currentUser = requireCurrentUser();
        var result = userService.syncOwnDateFormat(
                currentUser.getId(),
                request.getDateFormat(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );
        UserDateFormatMutationDto response = result.isConflict()
                ? UserDateFormatMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.dateFormat(),
                        result.version(),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : UserDateFormatMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.dateFormat(),
                        result.version(),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    @GetMapping("/me/time-format")
    public ResponseEntity<UserTimeFormatSnapshotDto> getCurrentTimeFormatSnapshot() {
        User currentUser = requireCurrentUser();
        return ResponseEntity.ok(new UserTimeFormatSnapshotDto(
                buildProfileResourceKey(currentUser),
                currentUser.getTimeFormat(),
                safeVersion(currentUser)
        ));
    }

    @PutMapping("/me/time-format")
    public ResponseEntity<UserTimeFormatMutationDto> updateCurrentTimeFormat(@Valid @RequestBody UpdateUserTimeFormatRequestDto request) {
        User currentUser = requireCurrentUser();
        var result = userService.syncOwnTimeFormat(
                currentUser.getId(),
                request.getTimeFormat(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );
        UserTimeFormatMutationDto response = result.isConflict()
                ? UserTimeFormatMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.timeFormat(),
                        result.version(),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : UserTimeFormatMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        buildProfileResourceKey(currentUser),
                        result.timeFormat(),
                        result.version(),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDetailDto> updateUser(@PathVariable Long id,
                                                    @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                                    @Valid @RequestBody UpdateUserRequest request) {
        User currentUser = requireCurrentUser();
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        UserAuditSnapshot existingUser = UserAuditSnapshot.from(userService.findRequiredById(id));
        User updatedUser = userService.updateUser(id, request);
        recordUserUpdateAudit(currentUser, existingUser, updatedUser, request);
        return ResponseEntity.ok(UserDtoMapper.toDetail(updatedUser));
    }

    private void recordUserUpdateAudit(User currentUser, UserAuditSnapshot previousUser, User updatedUser, UpdateUserRequest request) {
        if (currentUser == null || previousUser == null || updatedUser == null) {
            return;
        }
        List<String> changes = new ArrayList<>();
        if (request.getName() != null && !request.getName().isBlank() && !request.getName().equals(previousUser.name())) {
            changes.add("name:%s->%s".formatted(safeValue(previousUser.name()), safeValue(updatedUser.getName())));
        }
        if (request.getRole() != null && !request.getRole().isBlank() && !request.getRole().equalsIgnoreCase(previousUser.role())) {
            changes.add("role:%s->%s".formatted(safeValue(previousUser.role()), safeValue(updatedUser.getRole())));
        }
        if (request.getEnabled() != null && previousUser.enabled() != updatedUser.isEnabled()) {
            changes.add("enabled:%s->%s".formatted(previousUser.enabled(), updatedUser.isEnabled()));
        }
        if (request.getSessionTimeoutMinutes() != null) {
            Integer previousTimeout = previousUser.sessionTimeoutMinutes();
            Integer updatedTimeout = updatedUser.getSessionTimeoutMinutes();
            if (!java.util.Objects.equals(previousTimeout, updatedTimeout)) {
                changes.add("sessionTimeoutMinutes:%s->%s".formatted(previousTimeout, updatedTimeout));
            }
        }
        if (request.getLocale() != null && !request.getLocale().isBlank() && !request.getLocale().equalsIgnoreCase(previousUser.locale())) {
            changes.add("locale:%s->%s".formatted(safeValue(previousUser.locale()), safeValue(updatedUser.getLocale())));
        }
        if (changes.isEmpty()) {
            return;
        }
        userAuditEventService.recordUpdateEvent(
                currentUser.getId(),
                updatedUser.getId(),
                "User updated by %d [%s]".formatted(currentUser.getId(), String.join(", ", changes))
        );
    }

    private String prefixUserAuditId(String auditId) {
        if (!StringUtils.hasText(auditId)) {
            return auditId;
        }
        return auditId.startsWith("user-") ? auditId : "user-" + auditId;
    }

    private String safeValue(String value) {
        return StringUtils.hasText(value) ? value : "null";
    }

    private String buildProfileResourceKey(User user) {
        return "profile:" + user.getEmail().trim().toLowerCase();
    }

    private int safeVersion(User user) {
        if (user == null || user.getVersion() == null || user.getVersion() < 0) {
            return 0;
        }
        return user.getVersion();
    }

    private record UserAuditSnapshot(String name, String role, boolean enabled, Integer sessionTimeoutMinutes, String locale) {
        private static UserAuditSnapshot from(User user) {
            return new UserAuditSnapshot(
                    user.getName(),
                    user.getRole(),
                    user.isEnabled(),
                    user.getSessionTimeoutMinutes(),
                    user.getLocale()
            );
        }
    }

    @PostMapping("/internal/provision")
    public ResponseEntity<UserDetailDto> provisionFromKeycloak(@Valid @RequestBody KeycloakUserProvisionRequest request) {
        requireServiceAuthority("PERM_users:internal");
        User user = userService.provisionFromKeycloak(request);
        return ResponseEntity.ok(UserDtoMapper.toDetail(user));
    }

    /**
     * Resolves the current request's backend {@link User} profile.
     *
     * <p>Delegates to the shared {@link CurrentUserResolver} — the single
     * source of truth shared with the legacy {@code UserController} and
     * {@code NotificationPreferencesControllerV1}. The resolver also acts
     * as the safety net for the Keycloak user lazy-provision bridge: an
     * M365 first-login with a valid JWT but no profile is auto-provisioned
     * (when the gate allows) instead of failing with
     * {@code 403 PROFILE_MISSING}.
     */
    private User requireCurrentUser() {
        return currentUserResolver.resolveCurrentUser();
    }

    private org.springframework.data.jpa.domain.Specification<User> buildAdvancedFilterSpecSafe(String advancedFilter) {
        if (advancedFilter == null || advancedFilter.isBlank()) return null;
        try {
            String decoded = java.net.URLDecoder.decode(advancedFilter, java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
            String logic = root.has("logic") ? root.get("logic").asText("and").toLowerCase() : "and";
            com.fasterxml.jackson.databind.JsonNode conds = root.get("conditions");
            if (conds == null || !conds.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
            }
            if (conds.isEmpty()) return null;

            java.util.List<org.springframework.data.jpa.domain.Specification<User>> specs = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode c : conds) {
                String field = c.has("field") ? c.get("field").asText() : null;
                String op = c.has("op") ? c.get("op").asText() : null;
                com.fasterxml.jackson.databind.JsonNode val = c.get("value");
                if (field == null || op == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_condition");
                }
                FieldType type = ALLOWED_FILTER_FIELDS.get(field);
                if (type == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_field");
                }
                org.springframework.data.jpa.domain.Specification<User> s;
                switch (op) {
                    case "equals":
                        s = (rootX, query, cb) -> cb.equal(rootX.get(field), parseValue(val, type));
                        break;
                    case "notEqual":
                        s = (rootX, query, cb) -> cb.notEqual(rootX.get(field), parseValue(val, type));
                        break;
                    case "contains":
                        if (type != FieldType.STRING)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.like(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "notContains":
                        if (type != FieldType.STRING)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.notLike(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "lessThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.lessThan(rootX.get(field), v);
                        };
                        break;
                    case "greaterThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.greaterThan(rootX.get(field), v);
                        };
                        break;
                    case "inRange":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        com.fasterxml.jackson.databind.JsonNode v2 = c.get("value2");
                        if (val == null || v2 == null) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_inrange");
                        }
                        s = (rootX, query, cb) -> {
                            Comparable v1c = (Comparable) parseValue(val, type);
                            Comparable v2c = (Comparable) parseValue(v2, type);
                            return cb.between(rootX.get(field), v1c, v2c);
                        };
                        break;
                    default:
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                }
                specs.add(s);
            }
            if (specs.isEmpty()) return null;
            org.springframework.data.jpa.domain.Specification<User> combined = specs.get(0);
            for (int i = 1; i < specs.size(); i++) {
                combined = "or".equals(logic) ? combined.or(specs.get(i)) : combined.and(specs.get(i));
            }
            return combined;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(UserControllerV1.class).warn("advancedFilter parse hatası", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
        }
    }

    private enum FieldType {STRING, DATE_TIME, NUMBER, BOOLEAN}

    private static final java.util.Map<String, FieldType> ALLOWED_FILTER_FIELDS = java.util.Map.of(
            "name", FieldType.STRING,
            "email", FieldType.STRING,
            "role", FieldType.STRING,
            "enabled", FieldType.BOOLEAN,
            "createDate", FieldType.DATE_TIME,
            "lastLogin", FieldType.DATE_TIME,
            "sessionTimeoutMinutes", FieldType.NUMBER
    );

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createDate");
        }
        String[] parts = sort.split(";");
        List<Sort.Order> orders = new ArrayList<>();
        for (String p : parts) {
            String[] fd = p.split(",");
            if (fd.length != 2) continue;
            String field = fd[0].trim();
            String dir = fd[1].trim().toLowerCase();
            if (!ALLOWED_SORT_FIELDS.contains(field)) continue;
            Sort.Direction direction = "desc".equals(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, field));
        }
        if (orders.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createDate");
        }
        return Sort.by(orders);
    }

    private static final java.util.Set<String> ALLOWED_SORT_FIELDS = java.util.Set.of(
            "createDate", "lastLogin", "email", "name", "role", "enabled"
    );

    private Object parseValue(com.fasterxml.jackson.databind.JsonNode val, FieldType type) {
        if (val == null || val.isNull()) return null;
        try {
            return switch (type) {
                case STRING -> val.asText();
                case BOOLEAN -> {
                    String t = val.asText().trim().toLowerCase();
                    if (!t.equals("true") && !t.equals("false")) throw new IllegalArgumentException("boolean");
                    yield Boolean.parseBoolean(t);
                }
                case NUMBER -> {
                    if (val.isNumber()) yield val.intValue();
                    yield Integer.parseInt(val.asText());
                }
                case DATE_TIME -> java.time.LocalDateTime.parse(val.asText());
            };
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_value");
        }
    }

    private void recordSearchMetrics(Timer.Sample sample,
                                     String modeTag,
                                     boolean hasSearchParam,
                                     boolean hasAdvancedFilterParam,
                                     boolean hasSortParam,
                                     String resultTag) {
        try {
            sample.stop(meterRegistry.timer("users.search", "mode", modeTag,
                    "search", String.valueOf(hasSearchParam),
                    "advancedFilter", String.valueOf(hasAdvancedFilterParam),
                    "sort", String.valueOf(hasSortParam),
                    "result", resultTag));
        } catch (Exception ignored) {
            // metrics optional
        }
    }

    private void requireServiceAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ServiceAuthenticationToken serviceAuth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service token gerekli");
        }
        boolean hasAuthority = serviceAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!hasAuthority) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Yetersiz servis yetkisi");
        }
    }

    private void requirePermissionWithCompanyScope(String permission, Long companyId) {
        // ScopeContext superAdmin bypass (OpenFGA or dev mode)
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.superAdmin()) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }

        // Check Spring Security authorities (includes LocalDevAnonymousAuthFilter grants)
        boolean hasAuthority = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(permission));
        if (hasAuthority) {
            return;
        }

        Jwt jwt = authentication.getPrincipal() instanceof Jwt j ? j : null;
        AuthorizationContext ctx = authorizationContextService.buildContext(jwt, new java.util.ArrayList<>(authentication.getAuthorities()));
        if (!ctx.hasPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu işlemi gerçekleştirmek için " + permission + " yetkisine sahip olmalısınız");
        }
        if (companyId != null && !ctx.canAccessCompany(companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu şirket için yetkiniz yok");
        }
    }
}
