# ADR-0014 — Audit Module Gating: AUDIT vs IMPERSONATION_AUDIT

> **Status**: Accepted (2026-05-10)
> **Context**: User Impersonation v1 (spec [2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md))
> **Implementation**: PR-D2 (this PR — supersedes PR-D #138 closed)
> **Cross-AI peer review**: Codex thread `019e10bf-5256-7b03-b108-5f6d5543e3ed` (iter-2 AGREE WITH AMENDMENTS, ready_for_impl=true)

## Context

The PR-D first attempt (#138, closed 2026-05-09) introduced a dedicated
`/api/audit/events/impersonation` endpoint and an `impersonation-audit-view`
flat permission. Codex peer review identified three P1 issues:

1. **Substring leak in `AuditEventService.listEvents()`**: filter logic
   `containsIgnoreCase("IMPERSONATION", action)` matched
   `NON_IMPERSONATION_*` and other non-canonical action codes containing
   the substring. The dedicated endpoint silently included rows it
   shouldn't, and conversely, when used to exclude on the generic feed,
   would also drop legitimate audit rows that happened to contain the
   word. Both directions break audit integrity.
2. **Module gate too broad**: the dedicated endpoint was guarded by
   `@RequireModule("AUDIT", "can_view")`. Any AUDIT viewer could read
   impersonation events even though the underlying threat surface
   (privilege escalation history, denylist hits, MFA-skipped admin
   sessions) is materially more sensitive than ordinary audit log.
3. **Inert permission constant**: PR-D seeded an `impersonation-audit-view`
   flat permission via `PermissionDataInitializer`, but the OpenFGA gate
   at runtime was still `AUDIT.can_view`. The constant was never read by
   any check. Governance debt with zero behavioral effect.

Plus a fourth leak surface noticed during PR-D2 design:

4. **Live SSE stream**: `/api/audit/events/live` is gated by
   `AUDIT.can_view` and re-publishes every recorded event. Without
   suppression, an AUDIT viewer subscribed to the SSE channel would
   receive `IMPERSONATION_*` events live — bypassing the dashboard's
   list/export gating entirely.

5. **id-shortcut leak**: `GET /api/audit/events?id=<id>` returns a
   single event by id without applying the action filter. An AUDIT
   viewer who guessed an impersonation event id would get the full
   record back.

## Decision

PR-D2 introduces **two-module gating with at-source impersonation
exclusion**:

### Module split

| Module | Audience | Granted by default to |
|---|---|---|
| `AUDIT` (existing) | Ordinary audit reviewers | Audit ops, security ops |
| `IMPERSONATION_AUDIT` (new) | Impersonation auditors only | ADMIN role (`can_manage` seeded) |

The new module uses the existing generic `type module` declaration in
`backend/openfga/model.fga` — **no model schema change**. Tuples shape:
`module:IMPERSONATION_AUDIT can_view|can_manage user:<id>`.

### Authoritative event classification

A single allowlist of 5 canonical impersonation action codes lives in
`com.example.permission.audit.ImpersonationActionPredicate` and mirrors
`ImpersonationAuditEventTypes` 1:1:

- `IMPERSONATION_STARTED`
- `IMPERSONATION_STOPPED`
- `IMPERSONATION_BLOCKED`
- `IMPERSONATION_FAILED`
- `IMPERSONATION_REVOKED`

**Impersonation classification and scoped action filtering never use
substring matching.** The predicate uses an exact-match allowlist for two
specific roles:

1. **Scope predicate** (`isImpersonationAction`) — used by
   `AuditEventService.matchesScope(...)` to include/exclude rows on the
   generic vs. dedicated feed. PR-D iter-1 P1 closed a substring leak
   here: under the old `containsIgnoreCase("IMPERSONATION", ...)` matcher,
   any action code containing the literal "IMPERSONATION" (e.g.
   `NON_IMPERSONATION_STARTED`) was misclassified — leaking out of the
   generic feed and bleeding into the dedicated feed. PR-D2 codifies the
   exact-match allowlist via `ImpersonationActionPredicate.ALLOWED`.
   `NON_IMPERSONATION_STARTED` and similar codes correctly land in the
   GENERIC_AUDIT scope (regression test:
   `AuditEventServiceTest.listEvents_genericScope_excludesImpersonationActions`).

2. **Dedicated-endpoint filter validation** (`isAllowedImpersonationFilter`)
   — used by `AuditEventController.validateImpersonationActionFilter(...)`
   to 400 fabricated codes like `FOO_IMPERSONATION_BAR` on the
   `/impersonation` endpoint. The previous "silently rewrite to
   IMPERSONATION" behavior is gone.

**Out of scope (intentional, non-security path)**: the generic
`AuditEventService.matchesFilters(...)` continues to use case-insensitive
substring containment for `userEmail`, `service`, `search`, and the
generic-scope `action` filter. These are user-experience filters on
non-impersonation rows (impersonation rows have already been excluded by
`matchesScope` upstream), not a security boundary. Substring usage there
cannot leak impersonation rows because no impersonation row reaches that
code path in GENERIC_AUDIT scope.

### Scope-aware service layer

`AuditReadScope` enum (`GENERIC_AUDIT` / `IMPERSONATION_AUDIT`) flows
through every read method on `AuditEventService`:

- `listEvents(..., scope)` — filters by scope predicate
- `findByIdPage(id, scope)` — id-shortcut returns 404 when the loaded
  event is in the wrong scope (closes leak #5)
- `exportEvents(..., scope)` — payload built scope-aware
- `createExportJob(..., scope)` — scope embedded in job creation; the
  `__audit_read_scope` marker is persisted inside `filter_snapshot` JSON
  so download/get can re-validate scope (PR-D2 iter-3 absorb)
- `getCompletedExportJob(jobId, requestedBy, scope)` — fail-closed
  re-validation: a legacy pre-PR-D2 job (no scope marker) or a job whose
  marker does not match the requested scope returns 404; a defensive
  second pass re-deserializes JSON payloads on GENERIC_AUDIT downloads
  and strips any `IMPERSONATION_*` row that somehow slipped through
  (manual SQL edit, restored backup, future bug)
- `getExportJob(jobId, requestedBy, scope)` — same fail-closed scope
  guard for the status fetch path

### At-source live stream suppression

`AuditEventService.dispatchLiveEvent(...)` discards
`IMPERSONATION_*` events before publishing to the
`AUDIT.can_view`-gated SSE channel (closes leak #4). A separate
IMPERSONATION_AUDIT live stream may be added in a follow-up if needed
(per-emitter authz deferred per Codex iter-2 amendment).

### Strict action filter on dedicated endpoint

`/api/audit/events/impersonation?filter[action]=…` accepts only:
- `null` (absent) — returns all 5
- `"IMPERSONATION"` (alias) — returns all 5
- one of the 5 canonical codes — exact match

Anything else returns **400 Bad Request**. The previous "silently rewrite
to `IMPERSONATION`" behavior is removed.

### Granule-managed ADMIN seed

The flat `impersonation-audit-view` permission is **gone**. The
authoritative seed path is `PermissionDataInitializer.DEFAULT_ROLE_GRANULES`
which adds `MODULE:IMPERSONATION_AUDIT:MANAGE` to the ADMIN role. The
initializer publishes a `RoleChangeEvent` after seeding; the
`RoleChangeEventHandler` runs `TupleSyncService.propagateRoleChange()`
**after-commit** (BEFORE_COMMIT outbox + AFTER_COMMIT async) so the
OpenFGA tuple write happens outside the seed transaction.

Raw FGA tuples in `backend/openfga/tuples-seed.json` for feature gating
are **forbidden** (CNS-20260415-004). The granule seed → outbox →
async tuple write is the only sanctioned path.

#### Why MANAGE (not VIEW) for ADMIN?

ADMIN is granted `IMPERSONATION_AUDIT.can_manage` (not just `can_view`).
Two reasons:

1. **OpenFGA model**: `can_manage` implies `can_view` via the relation
   union `define can_view as viewer or can_manage` declared on the
   generic `module` type in `backend/openfga/model.fga`. A single
   MANAGE grant on ADMIN is therefore sufficient for both reading the
   dashboard and (when the feature lands) revoking active impersonation
   sessions or deleting impersonation history.
2. **Role contract**: ADMIN is the highest-privilege role in the
   platform; granting it the dashboard-only `can_view` would be
   surprising to operators who expect ADMIN to retain full control over
   security-sensitive features by default. Lower-privilege auditor
   roles (future: `IMPERSONATION_AUDITOR`) can be defined with `can_view`
   only — see Negative / open work below.

#### ADMIN org-admin bypass — explicit assumption

`RequireModuleInterceptor.isOrganizationAdmin` short-circuits any
`@RequireModule` check for users flagged as organization administrators.
This means freshly-bootstrapped ADMIN users (who are also organization
admins by convention in this platform) gain `IMPERSONATION_AUDIT.can_view`
access immediately on login, even before the async outbox writes the
`module:IMPERSONATION_AUDIT can_manage user:<adminId>` tuple to OpenFGA.
This is a deliberate ergonomics decision — without it, the
seed-transaction-vs-outbox-poll-cadence gap (typically seconds, but up
to the configured poll interval) would leave the dashboard inaccessible
right after a fresh DB rebuild.

Tenants that disable the `isOrganizationAdmin` bypass (planned future
flag, not implemented in PR-D2) will see the bypass-free path: ADMIN
users wait until the outbox poller propagates the granule before the
dashboard becomes accessible. Test coverage:
`PermissionDataInitializerImpersonationAdminSeedTest` asserts the
granule row insert + `RoleChangeEvent` publication shape that drives
the outbox.

## Consequences

### Positive

- **Defense in depth**: 6 leak channels closed:
  1. **Substring leak** in impersonation event classification (PR-D
     iter-1 P1) — although in practice this was an inert design vector
     even before PR-D2 because no concrete production code path
     exercised it adversarially, PR-D2 codifies the closure via the
     central allowlist (`ImpersonationActionPredicate.ALLOWED`) so
     future read paths cannot reintroduce it.
  2. **Broad gate** on the dedicated endpoint — replaced
     `AUDIT.can_view` with `IMPERSONATION_AUDIT.can_view`.
  3. **Inert permission** (`impersonation-audit-view` flat row never
     read by any check) — removed; granule-managed seed is the only
     path.
  4. **Live SSE** — `dispatchLiveEvent` suppresses `IMPERSONATION_*`
     events at source.
  5. **id-shortcut** — `findByIdPage(..., scope)` returns 404 across
     scope.
  6. **Persisted export-job payload** (PR-D2 iter-3 absorb) —
     `getCompletedExportJob(..., scope)` fails closed on legacy or
     mismatched-scope jobs and defensively re-filters JSON payloads.

  Generic AUDIT feed is now a pure non-impersonation view by
  construction, not by hopeful filtering.
- **Audit integrity**: `NON_IMPERSONATION_*` rows are no longer
  accidentally dropped by the generic feed (substring matcher gone).
- **Governance**: granule-managed seed obeys the
  no-raw-tuple-seed rule; future role rollouts go through
  `AccessRoleService.updateRoleGranules` consistently.
- **Forward compat**: a new impersonation event type only requires
  extending `ImpersonationAuditEventTypes` + `ImpersonationActionPredicate.ALLOWED`
  (drift guard test catches misalignment).

### Negative / open work

- **No tenant-aware impersonation audit (V19 schema)**: PR-D2 does not
  implement tenant-scoped impersonation audit. The V19 migration adds
  `impersonation_sessions` without an org/tenant column, and
  `permission_audit_events` likewise has no organization key on the
  impersonation rows. Consequences:

  - A cross-org admin with `IMPERSONATION_AUDIT.can_view` reads
    impersonation events from every organization in a single feed.
    There is no per-org filtering, no `tenant:X#impersonation_audit_viewer`
    tuple, no row-level security in the SQL.
  - This is acceptable for the current single-tenant pre-production
    deployment, but is a hard blocker for multi-tenant SaaS use.

  **Follow-up: V20 schema migration** is required before multi-tenant
  rollout. V20 must:
  1. Add `organization_id` (or `tenant_id`) column to
     `impersonation_sessions` and to the impersonation rows in
     `permission_audit_events` (backfilled from the impersonator's
     org membership at session start).
  2. Introduce a `tenant` type into `backend/openfga/model.fga` with
     a relation `impersonation_audit_viewer` and tuple shape
     `tenant:X#impersonation_audit_viewer@user:Y`.
  3. Update `AuditEventService` read paths to filter by
     `tenant_id IN (orgs caller can view)` driven by FGA list-objects.
  4. Extend `IMPERSONATION_AUDIT` granule with a `tenant_id` scope so
     `PermissionDataInitializer` can seed per-tenant ADMIN grants
     instead of the global MANAGE used today.

  Tracked as security debt note in spec §3.4.5; this ADR is the
  authoritative pointer for the V20 follow-up.

- **Initializer tuple propagation lag**: the granule seed runs in the
  bootstrap transaction; the OpenFGA tuple is written async via the
  outbox. ADMIN users may briefly fail an `IMPERSONATION_AUDIT.can_view`
  check immediately after a fresh DB rebuild. The outbox poller closes
  the gap within the poll cadence (seconds). Acceptable for bootstrap
  because the 6 ADMIN users are already organization admins (bypass
  takes effect immediately via `RequireModuleInterceptor.isOrganizationAdmin`)
  — see the "ADMIN org-admin bypass — explicit assumption" subsection
  above.

### Alternatives considered

- **Option A (this ADR)**: New `IMPERSONATION_AUDIT` module + scope-aware
  service. Chosen.
- **Option B**: Keep `AUDIT` gate; add an action-level relation
  (`module:AUDIT impersonation_view@user:Y`). Rejected: requires OpenFGA
  model change (new relation on `module` type), bleeds the
  general-vs-special distinction into the generic feed, and complicates
  per-emitter authz design later.
- **Option C**: Move impersonation rows to a separate table. Rejected:
  reporting joins (`impersonation_sessions` ⋈ `permission_audit_events`)
  break, and the leak channels (substring, id-shortcut, live SSE) recur
  the moment any code path joins the table back into the audit feed.

## References

- Spec: [2026-05-user-impersonation-v1-spec.md §3.4](../plans/2026-05-user-impersonation-v1-spec.md)
- Implementation classes:
  - `permission-service/src/main/java/com/example/permission/audit/ImpersonationActionPredicate.java`
  - `permission-service/src/main/java/com/example/permission/audit/AuditReadScope.java`
  - `permission-service/src/main/java/com/example/permission/service/AuditEventService.java`
  - `permission-service/src/main/java/com/example/permission/controller/AuditEventController.java`
  - `permission-service/src/main/java/com/example/permission/config/PermissionDataInitializer.java`
- Codex peer review:
  - thread `019e10bf-5256-7b03-b108-5f6d5543e3ed` iter-1 REVISE (5 ana revize)
  - iter-2 AGREE WITH AMENDMENTS, `ready_for_impl=true` (5 amendment plana girer)
- Governance HARD RULE: CNS-20260415-004 (raw FGA tuple seed forbidden)
