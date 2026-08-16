package com.github.oinsio.gnomish.serveobservability;

/**
 * The snapshot's {@code instance} section (id/host/version): the full instance id (not merely
 * the stable name half the file lives under — design D2), the host it runs
 * on, and the running factory version. Kept as plain {@code String} fields
 * rather than coupling to {@code app.port.tracker.InstanceId} or a build-info
 * type: neither a hostname abstraction nor a factory-version abstraction
 * exists elsewhere in the codebase yet, and this document model must not
 * pull in wiring from later task groups (design phase note, task 1.1).
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
