package com.github.oinsio.gnomish.serveobservability;

/**
 * The snapshot's {@code instance} section (id/host/version): the full instance id (not merely
 * the stable name half the file lives under — design D2), the host it runs
 * on, and the running factory version. Kept as plain {@code String} fields
 * rather than {@code app.port.tracker.InstanceId} or {@code app.FactoryVersion}:
 * this is also the read model {@code SnapshotJsonReader} fills from any
 * instance's snapshot file, so its fields are recorded text, not the running
 * process's values — a typed field would wrap that text and exclude nothing.
 * The running version enters only through {@code ObservabilityAssembly}, from
 * {@code FactoryVersion.current()} (design D2 of add-release-pipeline).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3, FR9 of add-serve-observability.
 *
 * @param instanceId the full {@code <name>-<suffix>} instance id; never blank
 * @param host the host the process runs on; never blank
 * @param factoryVersion the running factory build version; never blank
 */
public record InstanceInfo(String instanceId, String host, String factoryVersion) {}
