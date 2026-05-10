package com.example.commonauth;

/**
 * OpenFGA canonical constants for module-based authorization.
 * All legacy deprecated constants removed in Dalga 5 cleanup (TB-20).
 *
 * Usage:
 *   authzService.check(userId, RELATION_CAN_VIEW, "module", MODULE_USER_MANAGEMENT)
 */
public final class PermissionCodes {
    private PermissionCodes() {
    }

    // OpenFGA Module Constants
    public static final String MODULE_USER_MANAGEMENT = "USER_MANAGEMENT";
    public static final String MODULE_ACCESS = "ACCESS";
    public static final String MODULE_AUDIT = "AUDIT";
    public static final String MODULE_REPORT = "REPORT";
    public static final String MODULE_WAREHOUSE = "WAREHOUSE";
    public static final String MODULE_PURCHASE = "PURCHASE";
    public static final String MODULE_THEME = "THEME";
    public static final String MODULE_COMPANY = "COMPANY";
    /**
     * User Impersonation v1 (PR-D2) — dedicated module key for impersonation
     * audit dashboard. Separate from generic AUDIT so an AUDIT viewer/manager
     * cannot read impersonation events through the dashboard or live stream.
     *
     * <p>Generic OpenFGA {@code type module} is reused (no new model type);
     * tuples shape: {@code module:IMPERSONATION_AUDIT can_view|can_manage user:<id>}.
     * Granule seed pattern (see {@link com.example.permission.config.PermissionDataInitializer})
     * is the authoritative seeding path — raw FGA tuple seeds in
     * {@code backend/openfga/tuples-seed.json} are forbidden (CNS-20260415-004).
     */
    public static final String MODULE_IMPERSONATION_AUDIT = "IMPERSONATION_AUDIT";

    // OpenFGA relations
    public static final String RELATION_CAN_VIEW = "can_view";
    public static final String RELATION_CAN_MANAGE = "can_manage";
    public static final String RELATION_ADMIN = "admin";
    public static final String RELATION_VIEWER = "viewer";
    public static final String RELATION_MANAGER = "manager";
    public static final String RELATION_EDITOR = "editor";
    public static final String RELATION_OPERATOR = "operator";
}
