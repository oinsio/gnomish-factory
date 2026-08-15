package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps the {@code tracker} section of {@code config.yaml} into the domain
 * {@link TrackerConfig}. Extracted from {@link PipelineMapper} for file size;
 * the behavior is unchanged.
 *
 * <p>Implements FR17, FR9 of add-tracker-port; FR3 of add-claim-heartbeat; FR6,
 * NFR-S3 of add-factory-serve.
 */
final class TrackerConfigMapper {

    /** FR17 of add-tracker-port: the core abort-fuse threshold default when the key is omitted. */
    private static final int DEFAULT_ABORT_THRESHOLD = 3;

    /** FR6 of add-factory-serve, design D3: the core WIP limit default when the key is omitted. */
    private static final int DEFAULT_WIP_LIMIT = 10;

    /** FR3 of add-claim-heartbeat: the {@code config.yaml} location stamped onto a bad heartbeat interval. */
    private static final String TRACKER_FILE = "config.yaml";

    /** FR3 of add-claim-heartbeat: the field locator for a malformed {@code heartbeat-interval}. */
    private static final String HEARTBEAT_INTERVAL_WHERE = "tracker.heartbeat-interval";

    private TrackerConfigMapper() {}

    /**
     * Maps the {@code tracker} core keys (FR17, FR9 of add-tracker-port; FR3 of
     * add-claim-heartbeat): {@code null} when the whole section is absent from
     * {@code config.yaml}; otherwise carries {@code type} through, defaults
     * {@code abort-threshold} to 3 and the heartbeat constants to 5 minutes / 3
     * when the section is present but a key is omitted, parses the
     * {@code heartbeat-interval} string to a {@link java.time.Duration} (a
     * malformed string appends a located error to {@code errors} and discards
     * the definition, mirroring the {@code external} timings), defaults
     * {@code wip-limit} to 10 (FR6, NFR-S3 of add-factory-serve — read only
     * from the DTO the loader parsed out of the factory's own clone), and
     * passes through the ONE raw subsection matching {@code type} (already
     * schema-validated at the seam, task 3.2/4.2) for downstream short-ref
     * expansion and adapter construction (task 5.15) to consume.
     */
    static @Nullable TrackerConfig map(@Nullable TrackerDto tracker, List<ConfigError> errors) {
        if (tracker == null) {
            return null;
        }
        int threshold = tracker.abortThreshold() == null ? DEFAULT_ABORT_THRESHOLD : tracker.abortThreshold();
        Duration interval = tracker.heartbeatInterval() == null
                ? TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL
                : DurationConfig.parse(TRACKER_FILE, HEARTBEAT_INTERVAL_WHERE, tracker.heartbeatInterval(), errors);
        int multiplier = tracker.heartbeatTtlMultiplier() == null
                ? TrackerConfig.DEFAULT_HEARTBEAT_TTL_MULTIPLIER
                : tracker.heartbeatTtlMultiplier();
        int wipLimit = tracker.wipLimit() == null ? DEFAULT_WIP_LIMIT : tracker.wipLimit();
        String type = PipelineMapper.orEmpty(tracker.type());
        return new TrackerConfig(
                type,
                threshold,
                interval,
                multiplier,
                wipLimit,
                PipelineMapper.castSubsection(tracker.subsections().get(type)));
    }
}
