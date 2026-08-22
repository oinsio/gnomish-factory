package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import com.github.oinsio.gnomish.serveobservability.LedgerLifecycleEvent;
import com.github.oinsio.gnomish.serveobservability.LedgerLine;
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage;
import com.github.oinsio.gnomish.serveobservability.LifecycleLine;
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import com.github.oinsio.gnomish.serveobservability.RunSummaryLine;
import com.github.oinsio.gnomish.serveobservability.SweepActionLine;
import com.github.oinsio.gnomish.serveobservability.SweepTickLine;
import com.github.oinsio.gnomish.serveobservability.TaskOutcome;
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link LedgerLine} to its JSON-contract DTO tree and serializes it
 * as one compact JSON line — the entry point for ledger JSONL rendering
 * (task 1.2). Unlike {@link SnapshotJsonMapper}'s pretty-printed document,
 * each ledger line is a single-line JSON object (JSONL, FR10). Every sealed
 * or enum domain type is mapped through an exhaustive switch with no {@code
 * default} arm, mirroring {@link SnapshotJsonMapper}'s idiom.
 *
 * <p>Implements FR10, FR11, FR12, FR13 conventions of add-serve-observability.
 */
public final class LedgerJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link LedgerJson#mapper()} instance. */
    public LedgerJsonMapper() {
        this.mapper = LedgerJson.mapper();
    }

    /**
     * Serializes {@code line} as one compact (non-pretty-printed) JSON line
     * matching the v1 contract — the shape an appender writes verbatim,
     * followed by a newline (later task group).
     *
     * @param line the ledger line to serialize; never null
     * @return the compact JSON document, no trailing newline
     */
    public String serialize(LedgerLine line) {
        Object dto =
                switch (line) {
                    case TaskOutcomeLine taskOutcome -> toDto(taskOutcome);
                    case LifecycleLine lifecycle -> toDto(lifecycle);
                    case RunSummaryLine runSummary -> toDto(runSummary);
                    case SweepActionLine sweepAction -> toDto(sweepAction);
                    case SweepTickLine sweepTick -> toDto(sweepTick);
                };
        try {
            return mapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice; wrap rather than declare
            // a checked exception on every caller.
            throw new IllegalStateException("failed to serialize LedgerLine", e);
        }
    }

    /**
     * Builds the JSON-contract DTO for a {@code taskOutcome} line.
     *
     * @param line the line to map; never null
     * @return the equivalent DTO
     */
    public TaskOutcomeLineDto toDto(TaskOutcomeLine line) {
        return new TaskOutcomeLineDto(
                1,
                "taskOutcome",
                InstanceDto.from(line.instance()),
                line.taskId(),
                toOutcome(line.outcome()),
                toParkReason(line.parkReason()),
                line.stage(),
                line.attemptsUsed(),
                line.startedAt().toString(),
                line.finishedAt().toString(),
                line.wallMillis(),
                toTokensByModel(line.tokensByModel()));
    }

    /**
     * Builds the JSON-contract DTO for a {@code lifecycle} line.
     *
     * @param line the line to map; never null
     * @return the equivalent DTO
     */
    public LifecycleLineDto toDto(LifecycleLine line) {
        return switch (line.event()) {
            case LedgerLifecycleEvent.Started ignored ->
                new LifecycleLineDto(
                        1,
                        "lifecycle",
                        InstanceDto.from(line.instance()),
                        line.at().toString(),
                        "started",
                        null);
            case LedgerLifecycleEvent.Stopped stopped ->
                new LifecycleLineDto(
                        1,
                        "lifecycle",
                        InstanceDto.from(line.instance()),
                        line.at().toString(),
                        "stopped",
                        stopped.reason());
        };
    }

    /**
     * Builds the JSON-contract DTO for a {@code runSummary} line.
     *
     * @param line the line to map; never null
     * @return the equivalent DTO
     */
    public RunSummaryLineDto toDto(RunSummaryLine line) {
        return new RunSummaryLineDto(
                1,
                "runSummary",
                InstanceDto.from(line.instance()),
                line.startedAt().toString(),
                line.finishedAt().toString(),
                line.wallMillis(),
                toCounts(line.counts()),
                toTokensByModel(line.tokensByModel()));
    }

    /**
     * Builds the JSON-contract DTO for a {@code sweepAction} line (NFR-O2 of
     * add-serve-sandbox-lifecycle).
     *
     * @param line the line to map; never null
     * @return the equivalent DTO
     */
    public SweepActionLineDto toDto(SweepActionLine line) {
        return new SweepActionLineDto(
                1,
                "sweepAction",
                InstanceDto.from(line.instance()),
                line.at().toString(),
                line.objectName(),
                line.role(),
                line.mode(),
                line.taskKey(),
                toCategory(line.category()),
                line.reason(),
                line.age() == null ? null : line.age().toSeconds());
    }

    /**
     * Builds the JSON-contract DTO for a {@code sweepTick} line (NFR-O2 of
     * add-serve-sandbox-lifecycle).
     *
     * @param line the line to map; never null
     * @return the equivalent DTO
     */
    public SweepTickLineDto toDto(SweepTickLine line) {
        return new SweepTickLineDto(
                1,
                "sweepTick",
                InstanceDto.from(line.instance()),
                line.at().toString(),
                SnapshotJsonMapper.toSweepCounts(line.counts()));
    }

    /**
     * The one place the verdict vocabulary reaches the wire, so the ledger and the snapshot's
     * {@code vitals.sweep.counts} keys stay the same words (FR9's "no near-synonyms across
     * sinks").
     */
    static String toCategory(SweepVerdictCategory category) {
        return switch (category) {
            case CHECKED_ALIVE -> "checkedAlive";
            case KEPT_UNDER_THRESHOLD -> "keptUnderThreshold";
            case STOPPED_ORPHAN -> "stoppedOrphan";
            case DISPOSED_AGED -> "disposedAged";
            case DISPOSED_RECONSTRUCTIBLE -> "disposedReconstructible";
            case SKIPPED_NO_VERDICT -> "skippedNoVerdict";
        };
    }

    private static String toOutcome(TaskOutcome outcome) {
        return switch (outcome) {
            case DELIVERED -> "delivered";
            case AWAITING_HUMAN -> "awaitingHuman";
            case ABORTED -> "aborted";
            case REVOKED -> "revoked";
        };
    }

    private static @Nullable String toParkReason(@Nullable ParkReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case ESCALATION -> "escalation";
            case CHECKPOINT -> "checkpoint";
            case INFRA -> "infra";
        };
    }

    private static OutcomeCountsDto toCounts(OutcomeCounts counts) {
        return new OutcomeCountsDto(counts.delivered(), counts.awaitingHuman(), counts.aborted(), counts.revoked());
    }

    private static Map<String, TokenUsageDto> toTokensByModel(Map<String, LedgerTokenUsage> tokensByModel) {
        Map<String, TokenUsageDto> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, tokens) -> result.put(model, toTokenUsage(tokens)));
        return result;
    }

    private static TokenUsageDto toTokenUsage(LedgerTokenUsage tokens) {
        return new TokenUsageDto(tokens.input(), tokens.output(), tokens.cacheCreation(), tokens.cacheRead());
    }
}
