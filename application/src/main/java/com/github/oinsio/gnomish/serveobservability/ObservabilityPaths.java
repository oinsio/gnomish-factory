package com.github.oinsio.gnomish.serveobservability;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * The deterministic file names inside an instance's serve directory (FR9, design D2 of
 * add-serve-observability): {@code snapshot.json} and the daily ledger files, computed purely with
 * no filesystem access. The directory itself is not computed here: it is the registered project's
 * {@code serveDir(instance)} under the factory home, keyed by the configured instance *name*
 * (stable across restarts) — never by the full per-process {@code InstanceId}, so a restart never
 * moves the files and the cron monitor (D9) never goes blind on the old path; the full instance id
 * still appears inside the written data, never in the path. Every caller receives that one
 * directory as a {@link Path} from its composition point (design D1 of add-project-registry).
 *
 * <p>Pure path computation only — no {@code mkdir}, no I/O; materializing the directory and
 * writing files is the writer's job.
 *
 * <p>Implements FR9 of add-serve-observability. Implements FR10 of add-project-registry.
 */
public final class ObservabilityPaths {

    private static final String SNAPSHOT_FILE_NAME = "snapshot.json";

    private ObservabilityPaths() {}

    /**
     * The snapshot file within the instance's serve directory.
     *
     * @param serveDir the instance's serve directory, {@code projects/<name>/serve/<instance>}
     * @return {@code <serveDir>/snapshot.json}; not checked for existence
     */
    public static Path snapshotFile(Path serveDir) {
        return serveDir.resolve(SNAPSHOT_FILE_NAME);
    }

    /**
     * The daily ledger file for {@code date} within the instance's serve directory (naming per
     * FR14 of add-serve-observability: {@code ledger-YYYY-MM-DD.jsonl}, UTC day boundary decided by
     * the caller).
     *
     * @param serveDir the instance's serve directory, {@code projects/<name>/serve/<instance>}
     * @param date the UTC calendar date of the ledger file
     * @return {@code <serveDir>/ledger-<date>.jsonl}; not checked for existence
     */
    public static Path ledgerFile(Path serveDir, LocalDate date) {
        return serveDir.resolve("ledger-" + date + ".jsonl");
    }
}
