package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.serveobservability.FeedPhase;
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot;
import com.github.oinsio.gnomish.serveobservability.HeartbeatState;
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.JanitorVital;
import com.github.oinsio.gnomish.serveobservability.LifecycleState;
import com.github.oinsio.gnomish.serveobservability.ReaperVital;
import com.github.oinsio.gnomish.serveobservability.SlotEntry;
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.TrackerHealth;
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Parses the v1 snapshot JSON contract back into a {@link Snapshot} domain
 * object — the read-side counterpart of {@link SnapshotJsonMapper}
 * (task 1.1 of add-dashboard-page). Every wire enum string is mapped through
 * an exhaustive switch with a {@code default} arm that fails loudly on an
 * unrecognized value, since — unlike the write side's sealed/enum domain
 * types — the wire value is untyped {@code String} input that a future
 * contract version could legally extend.
 *
 * <p>Implements FR3, FR4 of add-dashboard-page.
 */
public final class SnapshotJsonReader {

    private final ObjectMapper mapper;

    /** Builds a reader backed by a fresh {@link SnapshotJson#mapper()} instance. */
    public SnapshotJsonReader() {
        this.mapper = SnapshotJson.mapper();
    }

    /**
     * Parses {@code json} as a v1 snapshot document.
     *
     * @param json the snapshot document text; never null
     * @return the equivalent {@link Snapshot} domain object
     * @throws JsonProcessingException if {@code json} is not valid JSON or
     *     does not match the {@link SnapshotDto} shape
     */
    public Snapshot read(String json) throws JsonProcessingException {
        return fromDto(mapper.readValue(json, SnapshotDto.class));
    }

    private static Snapshot fromDto(SnapshotDto dto) {
        return new Snapshot(
                dto.version(),
                Instant.parse(dto.writtenAt()),
                dto.intervalSeconds(),
                new InstanceInfo(
                        dto.instance().instanceId(),
                        dto.instance().host(),
                        dto.instance().factoryVersion()),
                fromLifecycle(dto.lifecycle()),
                fromFeed(dto.feed()),
                fromSlots(dto.slots()),
                fromVitals(dto.vitals()),
                fromTracker(dto.tracker()));
    }

    private static LifecycleState fromLifecycle(LifecycleDto dto) {
        return switch (dto.state()) {
            case "running" -> new LifecycleState.Running();
            case "draining" -> new LifecycleState.Draining();
            case "stopping" -> new LifecycleState.Stopping();
            case "stopped" -> new LifecycleState.Stopped(requireReason(dto.reason()));
            default -> throw new IllegalArgumentException("unknown lifecycle.state: " + dto.state());
        };
    }

    private static FeedSnapshot fromFeed(FeedDto dto) {
        return new FeedSnapshot(
                fromFeedState(dto.state()),
                Instant.parse(dto.since()),
                Instant.parse(dto.lastPollAt()),
                dto.openFronts(),
                dto.wipLimit());
    }

    private static FeedPhase fromFeedState(String state) {
        return switch (state) {
            case "filling" -> FeedPhase.FILLING;
            case "idleEmpty" -> FeedPhase.IDLE_EMPTY;
            case "idleBlocked" -> FeedPhase.IDLE_BLOCKED;
            case "full" -> FeedPhase.FULL;
            default -> throw new IllegalArgumentException("unknown feed.state: " + state);
        };
    }

    private static SlotsSnapshot fromSlots(SlotsDto dto) {
        return new SlotsSnapshot(
                dto.capacity(),
                dto.entries().stream()
                        .map(entry -> new SlotEntry(
                                entry.taskId(), entry.stage(), entry.attempt(), Instant.parse(entry.since())))
                        .toList());
    }

    private static VitalsSnapshot fromVitals(VitalsDto dto) {
        return new VitalsSnapshot(fromHeartbeat(dto.heartbeat()), fromReaper(dto.reaper()), fromJanitor(dto.janitor()));
    }

    private static HeartbeatVital fromHeartbeat(HeartbeatDto dto) {
        return new HeartbeatVital(fromHeartbeatState(dto.state()), Instant.parse(dto.lastTickAt()), dto.heldClaims());
    }

    private static HeartbeatState fromHeartbeatState(String state) {
        return switch (state) {
            case "idle" -> HeartbeatState.IDLE;
            case "running" -> HeartbeatState.RUNNING;
            case "died" -> HeartbeatState.DIED;
            default -> throw new IllegalArgumentException("unknown vitals.heartbeat.state: " + state);
        };
    }

    private static ReaperVital fromReaper(ReaperDto dto) {
        return new ReaperVital(Instant.parse(dto.lastRunAt()), dto.restartCount(), dto.intervalSeconds());
    }

    private static JanitorVital fromJanitor(JanitorDto dto) {
        return new JanitorVital(Instant.parse(dto.lastRunAt()));
    }

    private static TrackerHealth fromTracker(TrackerDto dto) {
        return new TrackerHealth(fromInstant(dto.lastSuccessAt()), dto.consecutiveFailures());
    }

    private static @Nullable Instant fromInstant(@Nullable String instant) {
        return instant == null ? null : Instant.parse(instant);
    }

    private static String requireReason(@Nullable String reason) {
        if (reason == null) {
            throw new IllegalArgumentException("lifecycle.reason must be present when state is \"stopped\"");
        }
        return reason;
    }
}
