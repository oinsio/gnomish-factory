package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.serveobservability.FeedPhase;
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot;
import com.github.oinsio.gnomish.serveobservability.HeartbeatState;
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital;
import com.github.oinsio.gnomish.serveobservability.JanitorVital;
import com.github.oinsio.gnomish.serveobservability.KeptEnvironmentEntry;
import com.github.oinsio.gnomish.serveobservability.LifecycleState;
import com.github.oinsio.gnomish.serveobservability.ReaperVital;
import com.github.oinsio.gnomish.serveobservability.SlotEntry;
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.SweepCounts;
import com.github.oinsio.gnomish.serveobservability.SweepVital;
import com.github.oinsio.gnomish.serveobservability.TrackerHealth;
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link Snapshot} to its JSON-contract DTO tree and serializes it —
 * the entry point for snapshot JSON rendering (task 1.1). Every sealed or
 * enum domain type is mapped through an exhaustive switch with no {@code
 * default} arm, mirroring {@code status.json.StatusReportJsonMapper}'s idiom:
 * a new variant fails to compile here until its mapping is added.
 *
 * <p>Implements FR2, FR3, FR10 conventions of add-serve-observability.
 */
public final class SnapshotJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link SnapshotJson#mapper()} instance. */
    public SnapshotJsonMapper() {
        this.mapper = SnapshotJson.mapper();
    }

    /**
     * Serializes {@code snapshot} as pretty-printed JSON matching the v1
     * contract.
     *
     * @param snapshot the snapshot to serialize; never null
     * @return the pretty-printed JSON document
     */
    public String serialize(Snapshot snapshot) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(snapshot));
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice; wrap rather than declare
            // a checked exception on every caller.
            throw new IllegalStateException("failed to serialize Snapshot", e);
        }
    }

    /**
     * Builds the JSON-contract DTO tree from {@code snapshot}.
     *
     * @param snapshot the snapshot to map; never null
     * @return the equivalent DTO tree
     */
    public SnapshotDto toDto(Snapshot snapshot) {
        return new SnapshotDto(
                snapshot.version(),
                snapshot.writtenAt().toString(),
                snapshot.intervalSeconds(),
                InstanceDto.from(snapshot.instance()),
                toLifecycle(snapshot.lifecycle()),
                toFeed(snapshot.feed()),
                toSlots(snapshot.slots()),
                toVitals(snapshot.vitals()),
                toTracker(snapshot.tracker()));
    }

    private static LifecycleDto toLifecycle(LifecycleState state) {
        return switch (state) {
            case LifecycleState.Running ignored -> new LifecycleDto("running", null);
            case LifecycleState.Draining ignored -> new LifecycleDto("draining", null);
            case LifecycleState.Stopping ignored -> new LifecycleDto("stopping", null);
            case LifecycleState.Stopped stopped -> new LifecycleDto("stopped", stopped.reason());
        };
    }

    private static FeedDto toFeed(FeedSnapshot feed) {
        return new FeedDto(
                toFeedState(feed.state()),
                feed.since().toString(),
                feed.lastPollAt().toString(),
                feed.openFronts(),
                feed.wipLimit());
    }

    private static String toFeedState(FeedPhase phase) {
        return switch (phase) {
            case FILLING -> "filling";
            case IDLE_EMPTY -> "idleEmpty";
            case IDLE_BLOCKED -> "idleBlocked";
            case FULL -> "full";
        };
    }

    private static SlotsDto toSlots(SlotsSnapshot slots) {
        return new SlotsDto(slots.capacity(), toEntries(slots.entries()));
    }

    private static List<SlotEntryDto> toEntries(List<SlotEntry> entries) {
        return entries.stream()
                .map(entry -> new SlotEntryDto(
                        entry.taskId(),
                        entry.stage(),
                        entry.attempt(),
                        entry.since().toString()))
                .toList();
    }

    private static VitalsDto toVitals(VitalsSnapshot vitals) {
        return new VitalsDto(
                toHeartbeat(vitals.heartbeat()),
                toReaper(vitals.reaper()),
                toJanitor(vitals.janitor()),
                toSweep(vitals.sweep()));
    }

    /** NFR-O1 of add-serve-sandbox-lifecycle: absent until the first sweep tick completes. */
    private static @Nullable SweepDto toSweep(@Nullable SweepVital sweep) {
        if (sweep == null) {
            return null;
        }
        return new SweepDto(
                sweep.lastTickAt().toString(),
                sweep.intervalSeconds(),
                toSweepCounts(sweep.counts()),
                sweep.kept().stream().map(SnapshotJsonMapper::toKept).toList(),
                sweep.keptTotal(),
                sweep.consecutiveSkippedTicks());
    }

    private static KeptEnvironmentDto toKept(KeptEnvironmentEntry entry) {
        return new KeptEnvironmentDto(entry.taskKey(), entry.ageSeconds(), entry.untilReapSeconds());
    }

    static SweepCountsDto toSweepCounts(SweepCounts counts) {
        return new SweepCountsDto(
                counts.checkedAlive(),
                counts.keptUnderThreshold(),
                counts.stoppedOrphan(),
                counts.disposedAged(),
                counts.disposedReconstructible(),
                counts.skippedNoVerdict());
    }

    private static HeartbeatDto toHeartbeat(HeartbeatVital heartbeat) {
        return new HeartbeatDto(
                toHeartbeatState(heartbeat.state()), heartbeat.lastTickAt().toString(), heartbeat.heldClaims());
    }

    private static String toHeartbeatState(HeartbeatState state) {
        return switch (state) {
            case IDLE -> "idle";
            case RUNNING -> "running";
            case DIED -> "died";
        };
    }

    private static ReaperDto toReaper(ReaperVital reaper) {
        return new ReaperDto(reaper.lastRunAt().toString(), reaper.restartCount(), reaper.intervalSeconds());
    }

    private static JanitorDto toJanitor(JanitorVital janitor) {
        return new JanitorDto(janitor.lastRunAt().toString());
    }

    private static TrackerDto toTracker(TrackerHealth tracker) {
        return new TrackerDto(toInstant(tracker.lastSuccessAt()), tracker.consecutiveFailures());
    }

    private static @Nullable String toInstant(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
