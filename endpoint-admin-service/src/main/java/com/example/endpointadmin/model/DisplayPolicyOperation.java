package com.example.endpointadmin.model;

/**
 * #508 Endpoint Display Policy operation.
 *
 * <ul>
 *   <li>{@code ENFORCE} — apply a managed screensaver + wallpaper desired-state.
 *       A {@code screensaver.enabled=false} ENFORCE is a managed-DISABLED policy,
 *       distinct from CLEAR.</li>
 *   <li>{@code CLEAR} — remove the backend-managed registry keys so the device
 *       returns to its unmanaged/default state. CLEAR carries no desired-state
 *       snapshot (see V58 {@code ck_*_clear_*} CHECKs).</li>
 * </ul>
 */
public enum DisplayPolicyOperation {
    ENFORCE,
    CLEAR
}
