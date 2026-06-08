package com.example.endpointadmin.model;

public enum CommandType {
    COLLECT_INVENTORY,
    LOCK_USER_LOGIN,
    UNLOCK_USER_LOGIN,
    CHANGE_LOCAL_PASSWORD,
    SMB_LIST_ALLOWED_PATH,
    SMB_READ_FILE_METADATA,
    SMB_DOWNLOAD_FILE,
    SMB_UPLOAD_FILE,
    ROTATE_CREDENTIAL,
    INSTALL_SOFTWARE,
    // AG-028 Phase 1 (Faz 22.5.6) — destructive-side companion to
    // INSTALL_SOFTWARE. V32 extends endpoint_commands.command_type CHECK
    // with this value. Dedicated dispatch path (Phase 1b service) lives
    // at POST /api/v1/admin/endpoint-devices/{id}/uninstalls/{rid}/approve;
    // generic /commands surface MUST reject it (Phase 1b
    // EndpointAdminCommandService.validateCommandType DEDICATED_PATH_ONLY
    // extension).
    UNINSTALL_SOFTWARE,
    // AG-029 (Faz 22.5.7) — signed agent self-update. Backend source now
    // recognizes the command type so generic command creation can reject it
    // fail-closed until the dedicated release-catalog/trust surface lands.
    UPDATE_AGENT,
    // #508 (Faz 22.5) — Endpoint Display Policy (screensaver + wallpaper)
    // enforcement. V58 extends endpoint_commands.command_type CHECK with this
    // value. Dedicated-path-only: the generic /commands surface rejects it
    // (422) until the dedicated display-policy dispatch surface lands (slice-2);
    // it carries a full desired-state snapshot and is always maker-checker.
    SET_DISPLAY_POLICY
}
