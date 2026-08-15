package com.github.oinsio.gnomish.serveobservability.json;

import com.github.oinsio.gnomish.serveobservability.InstanceInfo;

/**
 * The JSON contract's {@code instance} section (id/host/version): full instance id, host,
 * factory version (FR3, FR9).
 *
 * @param instanceId the full {@code <name>-<suffix>} instance id
 * @param host the host the process runs on
 * @param factoryVersion the running factory build version
 */
public record InstanceDto(String instanceId, String host, String factoryVersion) {

    /**
     * Maps an {@link InstanceInfo} to its DTO shape — shared by every mapper that embeds the
     * {@code instance} section (e.g. {@link SnapshotJsonMapper}, {@link LedgerJsonMapper}).
     *
     * @param instance the domain instance info to map; never null
     * @return the equivalent DTO
     */
    public static InstanceDto from(InstanceInfo instance) {
        return new InstanceDto(instance.instanceId(), instance.host(), instance.factoryVersion());
    }
}
