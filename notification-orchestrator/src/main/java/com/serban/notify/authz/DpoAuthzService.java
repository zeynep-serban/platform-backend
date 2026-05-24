package com.serban.notify.authz;

import com.serban.notify.config.NotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped DPO (Data Protection Officer) authorization service for
 * Faz 23.2.B PR-K6 (Codex thread {@code 019e59ea} iter-3 AGREE).
 *
 * <h3>Purpose</h3>
 *
 * <p>{@code AdminErasureController} previously gated on a global
 * {@code ROLE_PRIVACY_OFFICER} authority plus {@link
 * com.serban.notify.api.NotifyOrgAccessGuard} JWT tenant boundary. That
 * stack lets any privacy officer with multi-org {@code allowed_orgs}
 * erase data of any of those orgs — there is no "this user is DPO for
 * THIS specific org" least-privilege check. K6 closes that residual
 * with an OpenFGA tuple check on {@code organization#can_erasure}.
 *
 * <h3>Model contract</h3>
 *
 * <p>The OpenFGA authorization model (cutover 2026-05-23 via
 * {@code platform-k8s-gitops} PR #995, additive extension to follow for
 * K6 enable) MUST declare on the {@code organization} type:
 *
 * <pre>
 * type organization
 *   relations
 *     define admin: [user]
 *     define member: [user]
 *     define dpo: [user]
 *     define can_erasure: [user] or dpo or admin
 * </pre>
 *
 * <p>Operators seed per-org tuples via the permission-service tuple
 * writer:
 *
 * <pre>
 * organization:&lt;org-id&gt;#dpo@user:&lt;numeric-user-id&gt;
 * </pre>
 *
 * <p>{@code admin}-derivation is intentional defense-in-depth: an org
 * admin retains the operational erasure pathway when no dedicated DPO
 * has been designated (Codex iter-2 explicit policy choice; tested in
 * {@code AdminErasureControllerK6DpoAuthzTest}).
 *
 * <h3>Fail-closed contract</h3>
 *
 * <ol>
 *   <li>{@code dpoAuthzEnabled=false} (default) ⇒ silent pass; the
 *       legacy role+orgAccess stack remains primary.</li>
 *   <li>{@code dpoAuthzEnabled=true} ⇒ tuple check is mandatory:
 *       <ul>
 *         <li>Missing or blank {@code userId} (resolver returned
 *             {@code null}) ⇒ deny — prevents {@code Map.of(...)} NPE
 *             in {@link AuthzClient#check(String, String, String, String, String)}
 *             and surfaces an explicit fail-closed boundary.</li>
 *         <li>Blank {@code orgId} ⇒ deny — defensive guard; controller
 *             validation should catch this first.</li>
 *         <li>permission-service unreachable ⇒ deny (inherited from
 *             {@link AuthzClient} {@code authz_unreachable} contract).</li>
 *         <li>permission-service HTTP non-200 ⇒ deny.</li>
 *         <li>permission-service returns {@code allowed=false} ⇒ deny.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Defense-in-depth composition (current chain):
 *
 * <pre>
 * 1. JWT authn (SecurityConfig filter chain)
 * 2. @PreAuthorize hasAuthority('ROLE_PRIVACY_OFFICER')
 * 3. NotifyOrgAccessGuard.requireOrgAccessOrThrow(orgId)
 * 4. DpoAuthzService.canEraseForOrg(userId, orgId)  ← K6 new gate
 * 5. ErasureService
 * </pre>
 *
 * <p>The role gate stays a coarse-grain filter, the org guard enforces
 * JWT tenant boundary, and this K6 service adds least-privilege per-org
 * action authorization.
 */
@Service
public class DpoAuthzService {

    private static final Logger log = LoggerFactory.getLogger(DpoAuthzService.class);

    /** OpenFGA principal type for K6 user-bound tuples. */
    private static final String PRINCIPAL_TYPE = "user";

    /** OpenFGA relation name (matches the model {@code can_erasure} relation). */
    private static final String RELATION = "can_erasure";

    /** OpenFGA object type (matches the model {@code organization} type). */
    private static final String OBJECT_TYPE = "organization";

    private final AuthzClient authzClient;
    private final boolean enabled;

    public DpoAuthzService(AuthzClient authzClient, NotifyConfig notifyConfig) {
        this.authzClient = authzClient;
        this.enabled = notifyConfig.kvkk().dpoAuthzEnabled();
    }

    /**
     * Check whether the given user is authorized to erase data scoped to
     * the given org.
     *
     * <p>See class javadoc for the fail-closed contract; null/blank
     * inputs deny without touching the HTTP client (prevents
     * {@code Map.of(...)} NPE).
     *
     * @param userId numeric OpenFGA user id (NOT the Keycloak UUID;
     *               resolve via {@link DpoUserIdResolver})
     * @param orgId  organization id matching the {@code X-Org-Id} /
     *               request body {@code org_id}; the value already
     *               passed {@link com.serban.notify.api.NotifyOrgAccessGuard}
     *               tenant binding
     * @return {@code true} when the flag is off (legacy bypass) OR the
     *         OpenFGA Check resolves to allow; {@code false} fail-closed
     *         on any of the deny conditions
     */
    public boolean canEraseForOrg(String userId, String orgId) {
        if (!enabled) {
            // Codex 019e59ea AGREE: default-false rollout. Existing
            // ROLE_PRIVACY_OFFICER + NotifyOrgAccessGuard stack remains
            // primary until OpenFGA model promotion + tuple seeding +
            // burn-in evidence is captured per overlay.
            return true;
        }
        if (userId == null || userId.isBlank()) {
            log.warn("DPO authz fail-closed: missing/blank userId for orgId={}", orgId);
            return false;
        }
        if (orgId == null || orgId.isBlank()) {
            // Controller validation (@NotBlank on EraseRequest.orgId)
            // catches this first in production; defensive coverage for
            // direct service invocation in tests.
            log.warn("DPO authz fail-closed: missing/blank orgId");
            return false;
        }
        AuthzClient.AuthzDecision decision = authzClient.check(
            PRINCIPAL_TYPE, userId, RELATION, OBJECT_TYPE, orgId
        );
        if (log.isDebugEnabled()) {
            log.debug("DPO authz check: user:{} can_erasure organization:{} → allowed={} reason={}",
                userId, orgId, decision.allowed(), decision.reason());
        }
        return decision.allowed();
    }

    /**
     * Test/debug accessor — whether the K6 enforcement gate is currently
     * active. Production code should not branch on this; use {@link
     * #canEraseForOrg(String, String)} as the single decision point.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
