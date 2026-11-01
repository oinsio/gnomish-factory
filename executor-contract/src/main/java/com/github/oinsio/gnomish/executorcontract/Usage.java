package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a run consumed, as the executor reports it. {@link UsageProvenance#ABSENT} never renders as
 * zero: an executor that cannot report usage says so. The factory adds the role, the executor name
 * and the vote index when it records the entry. Wire shape: {@code {"provenance", "wallMillis"?,
 * "models"?: {<model id>: ModelUsage}, "tools"?: [ToolUsage]}}.
 *
 * <p>Implements NFR-O1 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
        UsageProvenance provenance,
        @Nullable Long wallMillis,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonSetter(nulls = Nulls.AS_EMPTY)
        Map<String, ModelUsage> models,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonSetter(nulls = Nulls.AS_EMPTY)
        List<ToolUsage> tools) {

    /** Refuses a negative wall time. */
    public Usage {
        WireChecks.atLeast(wallMillis, 0, "usage.wallMillis");
        models = Map.copyOf(models);
        tools = List.copyOf(tools);
    }

    /** The usage of an executor that reports none. */
    public static Usage absent() {
        return new Usage(UsageProvenance.ABSENT, null, Map.of(), List.of());
    }
}
