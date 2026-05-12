package com.example.user.controller;

import com.example.user.dto.InternalPasswordUpdateRequest;
import com.example.user.dto.InternalUserResponse;
import com.example.user.dto.PaginatedUserResponse;
import com.example.user.dto.RegisterRequest;
import com.example.user.dto.UpdateUserRequest;
import com.example.user.dto.UserResponse;
import com.example.user.model.User;
import com.example.commonauth.AuthorizationContext;
import com.example.user.authz.AuthorizationContextService;
import com.example.user.permission.PermissionActions;
import com.example.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.user.security.ServiceAuthenticationToken;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AuthorizationContextService authorizationContextService;
    private final com.example.user.service.UserAuditEventService userAuditEventService;
    private final com.example.user.service.CsvExportGuardService csvExportGuardService;
    private final MeterRegistry meterRegistry;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(UserController.class);

    public UserController(UserService userService,
                          AuthorizationContextService authorizationContextService,
                          com.example.user.service.UserAuditEventService userAuditEventService,
                          com.example.user.service.CsvExportGuardService csvExportGuardService,
                          MeterRegistry meterRegistry) {
        this.userService = userService;
        this.authorizationContextService = authorizationContextService;
        this.userAuditEventService = userAuditEventService;
        this.csvExportGuardService = csvExportGuardService;
        this.meterRegistry = meterRegistry;
    }

    private User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User u) {
            return u;
        }

        String username;
        if (principal instanceof Jwt jwt) {
            username = firstNonBlank(
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"),
                    authentication.getName());
        } else {
            username = authentication.getName();
        }

        if (!StringUtils.hasText(username)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        return userService.findByEmail(username)
                .orElseThrow(() -> {
                    LOGGER.warn("Keycloak kullanıcısı bulundu ancak yerel profil yok: {}", username);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Yeni kullanıcı kayıt endpoint'i.
     */
    @PostMapping("/register")
    @Deprecated
    public ResponseEntity<?> registerUser(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                          @RequestBody RegisterRequest registerRequest) {
        User currentUser = requireCurrentUser();

        requirePermissionWithCompanyScope(PermissionActions.USER_CREATE, companyId);

        try {
            User registeredUser = userService.registerUser(registerRequest);
            return new ResponseEntity<>(mapToUserResponse(registeredUser), HttpStatus.CREATED);
        } catch (IllegalStateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT); // 409 Conflict
        }
    }

    /**
     * Herkese açık kayıt endpoint'i. Hesap pasif olarak oluşturulur.
     */
    @PostMapping("/public/register")
    @Deprecated
    public ResponseEntity<?> registerUserPublic(@RequestBody RegisterRequest registerRequest) {
        try {
            User registeredUser = userService.registerUserPublic(registerRequest);
            return new ResponseEntity<>(mapToUserResponse(registeredUser), HttpStatus.CREATED);
        } catch (IllegalStateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    /**
     * Email'e göre kullanıcı getiren endpoint.
     */
    @GetMapping("/by-email/{email}")
    @Deprecated
    public ResponseEntity<UserResponse> getUserByEmail(@PathVariable String email) {
        return userService.findByEmail(email)
                .map(user -> ResponseEntity.ok(mapToUserResponse(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Internal endpoint for backend-authoritative Keycloak subject
     * resolution. Codex thread {@code 019e1bed} REVISE-1 absorb: the
     * public {@code GET /api/v1/users/{id}} endpoint must not expose
     * {@code kc_subject} to browsers; this service-token protected path
     * keeps the KC UUID surface server-to-server only (auth-service
     * {@code UserServiceClient.findUserById} → impersonation start flow).
     */
    @GetMapping("/internal/{userId}/impersonation-target")
    public ResponseEntity<com.example.user.dto.ImpersonationTargetResponse> getImpersonationTargetInternal(
            @PathVariable Long userId) {
        requireServiceAuthority("PERM_users:internal");
        try {
            User user = userService.findRequiredById(userId);
            return ResponseEntity.ok(new com.example.user.dto.ImpersonationTargetResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getKcSubject(),
                    user.isEnabled()
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Internal endpoint for trusted services that require credential details.
     */
    @GetMapping("/internal/by-email/{email}")
    public ResponseEntity<InternalUserResponse> getUserByEmailInternal(
            @PathVariable String email) {
        requireServiceAuthority("PERM_users:internal");

        return userService.findByEmail(email)
                .map(user -> ResponseEntity.ok(
                        new InternalUserResponse(
                                user.getId(),
                                user.getEmail(),
                                user.getPassword(),
                                user.getRole(),
                                user.isEnabled(),
                                user.getSessionTimeoutMinutes() != null
                                        ? user.getSessionTimeoutMinutes()
                                        : User.DEFAULT_SESSION_TIMEOUT_MINUTES
                        )
                ))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/internal/{userId}/activate")
    public ResponseEntity<Void> activateUserInternal(@PathVariable Long userId) {
        requireServiceAuthority("PERM_users:internal");
        userService.activateUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/{userId}/password")
    public ResponseEntity<Void> updatePasswordInternal(@PathVariable Long userId,
                                                       @Valid @RequestBody InternalPasswordUpdateRequest request) {
        requireServiceAuthority("PERM_users:internal");
        userService.updatePasswordInternal(userId, request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/{userId}/last-login")
    public ResponseEntity<Void> updateLastLoginInternal(@PathVariable Long userId) {
        requireServiceAuthority("PERM_users:internal");
        userService.updateLastLogin(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Tüm kullanıcıları listeleyen endpoint. Sadece ADMIN rolü erişebilir.
     */
    @GetMapping("/all")
    @Deprecated
    public ResponseEntity<PaginatedUserResponse> getAllUsers(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                                             @RequestParam(value = "search", required = false) String search,
                                                             @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
                                                             @RequestParam(value = "role", required = false, defaultValue = "ALL") String role,
                                                             @RequestParam(value = "sort", required = false) String sort,
                                                             @RequestParam(value = "advancedFilter", required = false) String advancedFilter,
                                                             @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                             @RequestParam(value = "pageSize", required = false, defaultValue = "25") int pageSize,
                                                             @RequestParam(value = "dataSource", required = false) String dataSource) {
        User currentUser = requireCurrentUser();
        Timer.Sample sample = Timer.start(meterRegistry);
        String resultTag = "success";
        boolean hasSearchParam = StringUtils.hasText(search);
        boolean hasAdvancedFilterParam = StringUtils.hasText(advancedFilter);
        boolean hasSortParam = StringUtils.hasText(sort);

        requirePermissionWithCompanyScope(PermissionActions.USER_READ, companyId);

        Sort parsedSort = parseSort(sort);
        org.springframework.data.jpa.domain.Specification<User> extraSpec = buildAdvancedFilterSpecSafe(advancedFilter);
        boolean clientMode = dataSource != null && dataSource.equalsIgnoreCase("client");
        boolean unpaged = clientMode || pageSize <= 0;
        String modeTag = unpaged ? "client" : "server";

        try {
            if (unpaged) {
                List<UserResponse> items = userService.searchUsers(search, status, role, extraSpec, parsedSort).stream()
                        .map(this::mapToUserResponse)
                        .collect(Collectors.toList());
                PaginatedUserResponse response = new PaginatedUserResponse();
                response.setItems(items);
                response.setTotal(items.size());
                response.setPage(1);
                response.setPageSize(items.size());
                return ResponseEntity.ok(response);
            }

            int safePage = Math.max(page, 1) - 1;
            int safePageSize = Math.max(pageSize, 1);
            Pageable pageable = PageRequest.of(safePage, safePageSize, parsedSort);

            Page<User> result = userService.searchUsers(search, status, role, extraSpec, pageable);

            List<UserResponse> items = result.getContent().stream()
                    .map(this::mapToUserResponse)
                    .collect(Collectors.toList());

            PaginatedUserResponse response = new PaginatedUserResponse();
            response.setItems(items);
            response.setTotal(result.getTotalElements());
            response.setPage(result.getNumber() + 1);
            response.setPageSize(result.getSize());

            return ResponseEntity.ok(response);
        } catch (ResponseStatusException ex) {
            resultTag = "error";
            throw ex;
        } finally {
            recordSearchMetrics(sample, modeTag, hasSearchParam, hasAdvancedFilterParam, hasSortParam, resultTag);
        }
    }

    /**
     * Büyük veri export'u için CSV streaming endpoint'i.
     */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public void exportCsv(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                          @RequestHeader(value = "X-Project-Id", required = false) Long projectId,
                          @RequestHeader(value = "X-Warehouse-Id", required = false) Long warehouseId,
                          @RequestParam(value = "search", required = false) String search,
                          @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
                          @RequestParam(value = "role", required = false, defaultValue = "ALL") String role,
                          @RequestParam(value = "sort", required = false) String sort,
        @RequestParam(value = "advancedFilter", required = false) String advancedFilter,
        jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        User currentUser = requireCurrentUser();
        requirePermissionWithCompanyScope(PermissionActions.USER_EXPORT, companyId);
        csvExportGuardService.assertWithinLimit(currentUser);

        // Basit yetki/log: export işlemi audit ile ilişkilendirilebilir
        org.slf4j.LoggerFactory.getLogger(UserController.class)
                .info("CSV export requested by userId={}, companyId={}, filters=search:{},status:{},role:{},sort:{}",
                        currentUser.getId(), companyId, search, status, role, sort);

        Sort parsedSort = parseSort(sort);
        org.springframework.data.jpa.domain.Specification<User> extraSpec = buildAdvancedFilterSpecSafe(advancedFilter);

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=users-export.csv");

        long exportedRows = 0L;
        long startedAt = System.currentTimeMillis();
        boolean success = false;
        String failureReason = null;
        try (java.io.Writer writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(response.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write('\uFEFF'); // UTF-8 BOM for Excel compatibility
            writer.write("ID;Ad Soyad;E-posta;Rol;Durum;Oluşturma Tarihi;Son Giriş\n");
            writer.flush();

            int page = 0;
            int pageSize = 500;
            while (true) {
                Pageable pageable = PageRequest.of(page, pageSize, parsedSort);
                Page<User> result = userService.searchUsers(search, status, role, extraSpec, pageable);
                for (User u : result.getContent()) {
                    String line = String.format("%s;%s;%s;%s;%s;%s;%s\n",
                            safe(u.getId()), safe(u.getName()), safe(u.getEmail()), safe(u.getRole()), safe(u.isEnabled()),
                            safe(u.getCreateDate()), safe(u.getLastLogin()));
                    writer.write(line);
                    exportedRows++;
                }
                writer.flush();
                if (result.isLast()) {
                    break;
                }
                page++;
            }
            success = true;
        } catch (Exception ex) {
            failureReason = ex.getClass().getSimpleName();
            if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
                failureReason += ":" + ex.getMessage();
            }
            throw ex;
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            csvExportGuardService.recordAudit(
                    currentUser,
                    search,
                    status,
                    role,
                    sort,
                    advancedFilter,
                    exportedRows,
                    durationMs,
                    success,
                    failureReason
            );
        }
    }

    // ── Excel (.xlsx) Export ──────────────────────────────────────────
    @GetMapping(value = "/export.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public void exportExcel(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                            @RequestHeader(value = "X-Project-Id", required = false) Long projectId,
                            @RequestHeader(value = "X-Warehouse-Id", required = false) Long warehouseId,
                            @RequestParam(value = "search", required = false) String search,
                            @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
                            @RequestParam(value = "role", required = false, defaultValue = "ALL") String role,
                            @RequestParam(value = "sort", required = false) String sort,
                            @RequestParam(value = "advancedFilter", required = false) String advancedFilter,
                            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        User currentUser = requireCurrentUser();
        requirePermissionWithCompanyScope(PermissionActions.USER_EXPORT, companyId);
        csvExportGuardService.assertWithinLimit(currentUser);

        org.slf4j.LoggerFactory.getLogger(UserController.class)
                .info("Excel export requested by userId={}, companyId={}", currentUser.getId(), companyId);

        Sort parsedSort = parseSort(sort);
        org.springframework.data.jpa.domain.Specification<User> extraSpec = buildAdvancedFilterSpecSafe(advancedFilter);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=users-export.xlsx");

        long exportedRows = 0L;
        long startedAt = System.currentTimeMillis();
        boolean success = false;
        String failureReason = null;
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook =
                     new org.apache.poi.xssf.streaming.SXSSFWorkbook(500)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Users");

            // Header row
            String[] headers = {"ID", "Ad Soyad", "E-posta", "Rol", "Durum", "Oluşturma Tarihi", "Son Giriş"};
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data rows — streaming page by page
            int rowIdx = 1;
            int page = 0;
            int pageSize = 500;
            while (true) {
                Pageable pageable = PageRequest.of(page, pageSize, parsedSort);
                Page<User> result = userService.searchUsers(search, status, role, extraSpec, pageable);
                for (User u : result.getContent()) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(u.getId() != null ? u.getId().doubleValue() : 0);
                    row.createCell(1).setCellValue(u.getName() != null ? u.getName() : "");
                    row.createCell(2).setCellValue(u.getEmail() != null ? u.getEmail() : "");
                    row.createCell(3).setCellValue(u.getRole() != null ? u.getRole() : "");
                    row.createCell(4).setCellValue(u.isEnabled() ? "ACTIVE" : "INACTIVE");
                    row.createCell(5).setCellValue(u.getCreateDate() != null ? u.getCreateDate().toString() : "");
                    row.createCell(6).setCellValue(u.getLastLogin() != null ? u.getLastLogin().toString() : "");
                    exportedRows++;
                }
                if (result.isLast()) break;
                page++;
            }

            workbook.write(response.getOutputStream());
            success = true;
        } catch (Exception ex) {
            failureReason = ex.getClass().getSimpleName();
            if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
                failureReason += ":" + ex.getMessage();
            }
            throw ex;
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            csvExportGuardService.recordAudit(currentUser, search, status, role, sort, advancedFilter,
                    exportedRows, durationMs, success, failureReason);
        }
    }

    private String safe(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        // Basit CSV kaçışı
        if (s.contains(",") || s.contains("\n") || s.contains("\"")) {
            s = '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static final java.util.Set<String> ALLOWED_SORT_FIELDS = java.util.Set.of(
            "createDate", "lastLogin", "email", "name", "role", "enabled"
    );

    private enum FieldType { STRING, DATE_TIME, NUMBER, BOOLEAN }

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
        java.util.List<Sort.Order> orders = new java.util.ArrayList<>();
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

    private org.springframework.data.jpa.domain.Specification<User> buildAdvancedFilterSpecSafe(String advancedFilter) {
        if (advancedFilter == null || advancedFilter.isBlank()) return null;
        try {
            String decoded = java.net.URLDecoder.decode(advancedFilter, java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
            // Beklenen basit model: { "logic": "and"|"or", "conditions": [{"field":"email","op":"contains","value":"@"}, ...] }
            String logic = root.has("logic") ? root.get("logic").asText("and").toLowerCase() : "and";
            com.fasterxml.jackson.databind.JsonNode conds = root.get("conditions");
            if (conds == null || !conds.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
            }
            if (conds.isEmpty()) return null; // boş filtre, yok sayılabilir

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
                        if (type != FieldType.STRING) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.like(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "notContains":
                        if (type != FieldType.STRING) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.notLike(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "lessThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.lessThan(rootX.get(field), v);
                        };
                        break;
                    case "greaterThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.greaterThan(rootX.get(field), v);
                        };
                        break;
                    case "inRange":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
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
            org.slf4j.LoggerFactory.getLogger(UserController.class).warn("advancedFilter parse hatası", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
        }
    }

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
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_type");
        }
    }

    /**
     * GEÇİCİ TEST METODU: Mevcut kullanıcının yetkilerini döndürür.
     */
    @GetMapping("/me/authorities")
    public ResponseEntity<?> getMyAuthorities() {
        // Güvenlik bağlamından mevcut kimlik doğrulama bilgisini alıyoruz.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Kimlik bilgisi bulunamadı.");
        }
        
        // Kullanıcının sahip olduğu tüm yetkileri (rolleri) alıyoruz.
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        
        // Bu bilgileri bir JSON olarak geri döndürüyoruz.
        return ResponseEntity.ok(
            Map.of(
                "username", authentication.getName(),
                "authorities", authorities.stream()
                                          .map(GrantedAuthority::getAuthority)
                                          .collect(Collectors.toList())
            )
        );
    }

    private UserResponse mapToUserResponse(User user) {
        // Codex 019e1bed REVISE-5 (hotfix): re-expose kcSubject after the
        // service-token internal endpoint hit a pre-existing user-service
        // KC issuer config drift. See UserResponse.kcSubject Javadoc.
        UserResponse response = new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreateDate(),
                user.getLastLogin(),
                user.getSessionTimeoutMinutes() != null
                        ? user.getSessionTimeoutMinutes()
                        : User.DEFAULT_SESSION_TIMEOUT_MINUTES
        );
        response.setKcSubject(user.getKcSubject());
        return response;
    }

    

    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long userId,
                                                   @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                                   @RequestHeader(value = "X-Project-Id", required = false) Long projectId,
                                                   @RequestHeader(value = "X-Warehouse-Id", required = false) Long warehouseId,
                                                   @RequestBody UpdateUserRequest request) {
        User currentUser = requireCurrentUser();
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);

        User updatedUser = userService.updateUser(userId, request);
        // Record audit and include auditId in response
        var savedEvent = userAuditEventService.recordUpdateEvent(
                currentUser.getId(),
                updatedUser.getId(),
                "User updated by %d".formatted(currentUser.getId())
        );
        UserResponse response = mapToUserResponse(updatedUser);
        if (savedEvent != null && savedEvent.getId() != null) {
            response.setAuditId(savedEvent.getId().toString());
        }
        return ResponseEntity.ok(response);
    }

    private void recordSearchMetrics(Timer.Sample sample,
                                     String modeTag,
                                     boolean hasSearch,
                                     boolean hasAdvancedFilter,
                                     boolean hasSort,
                                     String resultTag) {
        if (sample == null) {
            return;
        }
        String[] tags = new String[]{
                "mode", modeTag,
                "has_search", booleanTag(hasSearch),
                "has_advanced_filter", booleanTag(hasAdvancedFilter),
                "has_sort", booleanTag(hasSort),
                "result", resultTag
        };
        meterRegistry.counter("users_search_requests_total", tags).increment();
        sample.stop(Timer.builder("users_search_duration")
                .description("Users grid search duration")
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    private String booleanTag(boolean value) {
        return value ? "true" : "false";
    }

    private void requirePermissionWithCompanyScope(String permission, Long companyId) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.superAdmin()) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }

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
