package com.example.endpointadmin.model;

/**
 * AG-029 / BE-031 release catalog channel.
 *
 * <p>Channels are release metadata only. They do not dispatch UPDATE_AGENT or
 * select target devices until the later rollout/policy surface resolves them.
 */
public enum AgentUpdateChannel {
    STAGING,
    PILOT,
    STABLE
}
