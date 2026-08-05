package com.github.oinsio.gnomish.serveobservability;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * The deterministic observability directory/file formula (FR9, design D2):
 * {@code ~/.gnomish/serve/<instance-name>/} holds {@code snapshot.json} and the daily ledger
 * files, computed purely with no filesystem access. Keyed by the configured instance *name*
 * (stable across restarts, {@link com.github.oinsio.gnomish.FactoryProperties#instanceName()}) —
 * never by the full per-process {@code InstanceId}, so a restart never moves the files and the
 * cron monitor (D9) never goes blind on the old path; the full instance id still appears inside
 * the written data, never in the path (FR9, design D2).
 *
 * <p>Pure path computation only — no {@code mkdir}, no I/O; materializing the directory and
 * writing files is the writer's job (later task groups).
 *
 * <p>Implements FR9 of add-serve-observability.
 */
public final class ObservabilityPaths {

    private static final String SNAPSHOT_FILE_NAME = "snapshot.json";

    private ObservabilityPaths() {}

    /**
     * Computes the per-instance-name observability directory under the given home directory.
     *
     * @param homeDir the user's home directory, e.g. {@code Path.of(System.getProperty(
     *     "user.home"))}; production wiring passes the real home directory, tests pass a temp
     *     directory
     * @param instanceName the configured instance name (design D2); the stable half of {@code
     *     InstanceId}, not the full per-process id
     * @return the deterministic {@code <homeDir>/.gnomish/serve/<instance-name>/} directory; not
     *     checked for existence
     */
    public static Path directory(Path homeDir, String instanceName) {
        return homeDir.resolve(".gnomish").resolve("serve").resolve(instanceName);
    }

    /**
     * Computes the deterministic snapshot file path within the instance's observability
     * directory.
     *
     * @param homeDir the user's home directory
     * @param instanceName the configured instance name (design D2)
     * @return the deterministic {@code <homeDir>/.gnomish/serve/<instance-name>/snapshot.json}
     *     path; not checked for existence
     */
    public static Path snapshotFile(Path homeDir, String instanceName) {
        return directory(homeDir, instanceName).resolve(SNAPSHOT_FILE_NAME);
    }

    /**
     * Computes the deterministic daily ledger file path for {@code date} within the instance's
     * observability directory (naming per FR14: {@code ledger-YYYY-MM-DD.jsonl}, UTC day
     * boundary decided by the caller).
     *
     * @param homeDir the user's home directory
     * @param instanceName the configured instance name (design D2)
     * @param date the UTC calendar date of the ledger file
     * @return the deterministic {@code <homeDir>/.gnomish/serve/<instance-name>/ledger-<date>
     *     .jsonl} path; not checked for existence
     */
    public static Path ledgerFile(Path homeDir, String instanceName, LocalDate date) {
        return directory(homeDir, instanceName).resolve("ledger-" + date + ".jsonl");
    }
}
