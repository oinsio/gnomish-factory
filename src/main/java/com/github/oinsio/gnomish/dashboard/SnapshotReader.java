package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.LifecycleState;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads the serve daemon's {@code snapshot.json} into a
 * {@link DaemonSnapshotView} for the dashboard's daemon section (task 1.1).
 * A missing file, an unreadable file, or malformed JSON all degrade to
 * {@link DaemonSnapshotView.Absent} rather than throw — the daemon section
 * must never fail the render of the history or board sections composed
 * alongside it (FR3). {@code now} is an explicit parameter rather than an
 * injected clock port: no such seam exists elsewhere for a plain read-only
 * value computation like this one, and a parameter keeps the class a pure
 * function of its inputs, trivial to test without fakes.
 *
 * <p>Implements FR3, FR4 of add-dashboard-page.
 */
public final class SnapshotReader {

    /**
     * The staleness multiplier {@code k} (FR4, design D3): a snapshot is
     * stale once {@code now − writtenAt > k × intervalSeconds}.
     */
    private static final int STALENESS_MULTIPLIER = 3;

    private final SnapshotJsonReader jsonReader;

    /** Builds a reader backed by a fresh {@link SnapshotJsonReader} instance. */
    public SnapshotReader() {
        this.jsonReader = new SnapshotJsonReader();
    }

    /**
     * Reads {@code snapshotFile} and classifies it as of {@code now}.
     *
     * @param snapshotFile the daemon's {@code snapshot.json} path; never null
     * @param now the instant to measure staleness against; never null
     * @return {@link DaemonSnapshotView.Absent} if the file is missing,
     *     unreadable, or malformed; otherwise the classified view for the
     *     parsed snapshot (FR3, FR4)
     */
    public DaemonSnapshotView read(Path snapshotFile, Instant now) {
        String json;
        try {
            json = Files.readString(snapshotFile, StandardCharsets.UTF_8);
        } catch (IOException fileUnavailable) {
            return new DaemonSnapshotView.Absent();
        }

        Snapshot snapshot;
        try {
            snapshot = jsonReader.read(json);
        } catch (IOException | IllegalArgumentException | DateTimeException malformed) {
            // JsonProcessingException (checked) for structurally invalid JSON, an
            // IllegalArgumentException from SnapshotJsonReader for an unrecognized
            // enum wire value, or a DateTimeException for a malformed instant
            // string — all mean the file cannot be trusted, so degrade the same
            // as a missing file (FR3).
            return new DaemonSnapshotView.Absent();
        }

        if (!isStale(snapshot, now)) {
            return new DaemonSnapshotView.Fresh(snapshot);
        }
        return snapshot.lifecycle() instanceof LifecycleState.Stopped
                ? new DaemonSnapshotView.StoppedStale(snapshot)
                : new DaemonSnapshotView.DeadDaemon(snapshot);
    }

    private static boolean isStale(Snapshot snapshot, Instant now) {
        Duration age = Duration.between(snapshot.writtenAt(), now);
        Duration threshold = Duration.ofSeconds(snapshot.intervalSeconds() * STALENESS_MULTIPLIER);
        return age.compareTo(threshold) > 0;
    }
}
