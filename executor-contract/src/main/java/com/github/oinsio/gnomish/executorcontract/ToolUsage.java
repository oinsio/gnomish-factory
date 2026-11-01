package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * What a run spent in one tool: the call count and an optional total duration. Wire shape: {@code
 * {"name", "calls", "totalMillis"?}}.
 *
 * <p>Implements NFR-O1 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolUsage(
        String name,
        @JsonProperty(required = true) long calls,
        @Nullable Long totalMillis) {

    /** Refuses a blank name and a negative count or duration. */
    public ToolUsage {
        WireChecks.nonBlank(name, "tools[].name");
        WireChecks.atLeast(calls, 0, "tools[].calls");
        WireChecks.atLeast(totalMillis, 0, "tools[].totalMillis");
    }
}
