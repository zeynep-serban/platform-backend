package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleResponse;
import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.repository.EndpointProhibitedSoftwareRuleRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

/**
 * BE-025 — prohibited-software denylist rule CRUD service (Faz 22.5).
 *
 * <p>Manages the per-tenant {@link EndpointProhibitedSoftwareRule} rows
 * that {@link EndpointComplianceService} matches against the device
 * inventory. Operations are tenant-scoped and write
 * {@code createdBySubject / lastUpdatedBySubject} from the bound
 * {@link AdminTenantContext}.
 *
 * <p>Validation order on write: bean-validation (controller) →
 * {@link ProhibitedSoftwareRuleValidator#validate} (cross-field, → 400) →
 * duplicate pre-check (→ 409) → persist (the V19 functional UNIQUE index +
 * CHECK constraints are the structural backstop, surfaced as 409 / 400 on a
 * race).
 */
@Service
public class ProhibitedSoftwareRuleService {

    private final EndpointProhibitedSoftwareRuleRepository ruleRepository;
    private final ProhibitedSoftwareRuleValidator validator;

    public ProhibitedSoftwareRuleService(
            EndpointProhibitedSoftwareRuleRepository ruleRepository,
            ProhibitedSoftwareRuleValidator validator) {
        this.ruleRepository = ruleRepository;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public Page<ProhibitedSoftwareRuleResponse> list(AdminTenantContext tenant, Pageable pageable) {
        return ruleRepository
                .findByTenantIdOrderByLastUpdatedAtDesc(tenant.tenantId(), pageable)
                .map(ProhibitedSoftwareRuleResponse::from);
    }

    @Transactional(readOnly = true)
    public ProhibitedSoftwareRuleResponse get(AdminTenantContext tenant, UUID id) {
        return ruleRepository.findByIdAndTenantId(id, tenant.tenantId())
                .map(ProhibitedSoftwareRuleResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Prohibited-software rule not found."));
    }

    @Transactional
    public ProhibitedSoftwareRuleResponse create(
            AdminTenantContext tenant, ProhibitedSoftwareRuleRequest request) {
        validator.validate(request);

        String name = ProhibitedSoftwareRuleValidator.trimToNull(request.namePattern());
        String publisher = ProhibitedSoftwareRuleValidator.trimToNull(request.publisherPattern());
        requireNoDuplicate(tenant.tenantId(), request, name, publisher, null);

        EndpointProhibitedSoftwareRule rule = new EndpointProhibitedSoftwareRule();
        rule.setTenantId(tenant.tenantId());
        rule.setMatchType(request.matchType());
        rule.setMatchMode(request.matchMode());
        rule.setNamePattern(name);
        rule.setPublisherPattern(publisher);
        rule.setEnabled(request.enabled() == null ? true : request.enabled());
        rule.setNotes(ProhibitedSoftwareRuleValidator.trimToNull(request.notes()));
        rule.setCreatedBySubject(tenant.subject());
        rule.setLastUpdatedBySubject(tenant.subject());

        return persist(rule, tenant.tenantId());
    }

    @Transactional
    public ProhibitedSoftwareRuleResponse update(
            AdminTenantContext tenant, UUID id, ProhibitedSoftwareRuleRequest request) {
        validator.validate(request);

        EndpointProhibitedSoftwareRule rule = ruleRepository
                .findByIdAndTenantId(id, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Prohibited-software rule not found."));

        String name = ProhibitedSoftwareRuleValidator.trimToNull(request.namePattern());
        String publisher = ProhibitedSoftwareRuleValidator.trimToNull(request.publisherPattern());
        // Exclude this rule's own id so re-saving an unchanged rule is not a
        // self-collision.
        requireNoDuplicate(tenant.tenantId(), request, name, publisher, id);

        rule.setMatchType(request.matchType());
        rule.setMatchMode(request.matchMode());
        rule.setNamePattern(name);
        rule.setPublisherPattern(publisher);
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        rule.setNotes(ProhibitedSoftwareRuleValidator.trimToNull(request.notes()));
        rule.setLastUpdatedBySubject(tenant.subject());

        return persist(rule, tenant.tenantId());
    }

    @Transactional
    public void delete(AdminTenantContext tenant, UUID id) {
        EndpointProhibitedSoftwareRule rule = ruleRepository
                .findByIdAndTenantId(id, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Prohibited-software rule not found."));
        ruleRepository.delete(rule);
    }

    private void requireNoDuplicate(
            UUID tenantId, ProhibitedSoftwareRuleRequest request,
            String name, String publisher, UUID excludeId) {
        ruleRepository.findDuplicate(
                        tenantId,
                        request.matchType(),
                        request.matchMode(),
                        ProhibitedSoftwareRuleValidator.normalize(name),
                        ProhibitedSoftwareRuleValidator.normalize(publisher))
                .filter(existing -> excludeId == null
                        || !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "An equivalent prohibited-software rule already exists "
                                    + "for this tenant.");
                });
    }

    private ProhibitedSoftwareRuleResponse persist(
            EndpointProhibitedSoftwareRule rule, UUID tenantId) {
        try {
            EndpointProhibitedSoftwareRule saved = ruleRepository.save(rule);
            return ruleRepository.findByIdAndTenantId(saved.getId(), tenantId)
                    .map(ProhibitedSoftwareRuleResponse::from)
                    .orElseThrow(() -> new IllegalStateException(
                            "Saved prohibited-software rule disappeared after persist."));
        } catch (DataIntegrityViolationException ex) {
            throw mapDataIntegrityViolation(ex);
        }
    }

    /**
     * Map a persist-time {@link DataIntegrityViolationException} to the
     * correct HTTP status (Codex 019e763a REVISE #2): a functional UNIQUE
     * dedup race is a genuine duplicate (409), while a CHECK-constraint
     * violation is invalid input (400) and must NOT be mislabelled as a
     * duplicate. Routing is by the offending constraint name (primary) with
     * the SQLSTATE as a robust fallback when the name is unavailable:
     *
     * <ul>
     *   <li>{@code uq_*} / SQLSTATE {@code 23505} (unique_violation) → 409;</li>
     *   <li>{@code ck_*} / SQLSTATE {@code 23514} (check_violation)  → 400.</li>
     * </ul>
     *
     * <p>The app validator exhaustively mirrors every CHECK before persist
     * (matchType↔pattern presence, blank, CONTAINS min-length), so a CHECK
     * reaching here is effectively a non-application / corrupt-input path; it
     * is still surfaced as a precise 400 rather than a misleading 409. An
     * unclassifiable violation falls back to 409 (the historical default) so
     * the concurrent-equivalent-create race keeps its established behaviour.
     */
    private static ResponseStatusException mapDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        DbErrorContext ctx = extractDbContext(ex);
        String constraint = ctx.constraintName() == null
                ? "" : ctx.constraintName().toLowerCase(Locale.ROOT);
        boolean uniqueViolation = constraint.startsWith("uq_")
                || SQLSTATE_UNIQUE_VIOLATION.equals(ctx.sqlState());
        boolean checkViolation = constraint.startsWith("ck_")
                || SQLSTATE_CHECK_VIOLATION.equals(ctx.sqlState());
        if (checkViolation && !uniqueViolation) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Prohibited-software rule is invalid: it violates a field "
                            + "constraint (e.g. blank or too-short pattern).", ex);
        }
        // UNIQUE dedup race, or an unclassifiable violation (fail to the
        // historical 409 default rather than guessing 400 for an unknown
        // structural error).
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Prohibited-software rule could not be persisted: it conflicts "
                        + "with an existing equivalent rule for this tenant.", ex);
    }

    /** PostgreSQL SQLSTATE for unique_violation. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    /** PostgreSQL SQLSTATE for check_violation. */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    /**
     * Structural projection of a DB error — constraint name + SQLSTATE walked
     * out of the wrapped cause chain. Mirrors the
     * {@code permission-service AccessScopeService} extractor idiom so status
     * routing is driven by the actual offending constraint, not brittle
     * message substrings.
     */
    private record DbErrorContext(String sqlState, String constraintName) {
    }

    private static DbErrorContext extractDbContext(DataIntegrityViolationException ex) {
        String sqlState = null;
        String constraintName = null;
        Throwable cause = ex.getCause();
        int depth = 0;
        while (cause != null && depth < 10) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hcve) {
                if (hcve.getConstraintName() != null) {
                    constraintName = hcve.getConstraintName();
                }
                if (hcve.getSQLException() != null
                        && hcve.getSQLException().getSQLState() != null) {
                    sqlState = hcve.getSQLException().getSQLState();
                }
            }
            if (cause instanceof java.sql.SQLException sqle && sqle.getSQLState() != null) {
                sqlState = sqle.getSQLState();
            }
            cause = cause.getCause();
            depth++;
        }
        return new DbErrorContext(sqlState, constraintName);
    }
}
