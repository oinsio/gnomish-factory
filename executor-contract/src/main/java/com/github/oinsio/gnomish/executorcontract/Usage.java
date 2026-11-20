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

    // @DoNotMutate, JVMTI redefinition limit (testing.md): PIT's "replaced return value with null"
    // mutant of this factory ends its minion with "class redefinition failed: attempted to change the
    // class ... Record ... attribute" (RUN_ERROR, hcoles/pitest#1285), so no test can observe it. The
    // factory holds no decision; ExecutorResultReadSpec pins its fields one by one.
    /** The usage of an executor that reports none. */
    @DoNotMutate
    public static Usage absent() {
        return new Usage(UsageProvenance.ABSENT, null, Map.of(), List.of());
    }

    /**
     * The usage of an executor that mediates no model: a known zero — provenance {@code REPORTED},
     * no wall time, no model, no tool — so a task's usage fold stays complete and no per-model zero
     * is written (design D13 of migrate-verify-to-executor-contract; D8 of add-command-executor).
     */
    public static Usage knownZero() {
        return new Usage(UsageProvenance.REPORTED, null, Map.of(), List.of());
    }
}
