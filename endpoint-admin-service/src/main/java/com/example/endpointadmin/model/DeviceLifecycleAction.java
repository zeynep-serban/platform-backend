package com.example.endpointadmin.model;

/**
 * Device lifecycle transition action recorded in
 * {@code endpoint_device_lifecycle_audit} (V56).
 *
 * <p>DECOMMISSION sets a device to {@link DeviceStatus#DECOMMISSIONED} (KVKK:
 * deactivate, not delete — data retained); REACTIVATE reverses it. There is no
 * delete action: a device row is never removed by this lifecycle.
 */
public enum DeviceLifecycleAction {
    DECOMMISSION,
    REACTIVATE
}
