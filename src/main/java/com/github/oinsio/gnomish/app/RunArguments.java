package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed and first-tier-validated flags of one {@code gnomish run} invocation, produced by
 * {@link RunArgumentsParser} (design D5). This is a pure value: it carries no filesystem or
 * pipeline knowledge beyond what {@link RunArgumentsParser} itself checks — {@code project}'s
 * existence and {@code fromStage}'s validity against a loaded {@code PipelineDefinition} are
 * later tasks' concern (7.2/7.3, design D3/D4).
 *
 * <p>Implements FR1 of add-manual-run.
 *
 * @param project the workspace root; defaults to the current working directory when
 *     {@code --project} is absent (design D3); unresolved, not checked for existence here
 * @param taskSource the task description's origin, exactly one of inline text or a file path
 *     (FR1)
 * @param taskId the operator-supplied task id override, or {@code null} to let task synthesis
 *     (task 7.3, FR2) generate one; when present, validated against
 *     {@link RunArgumentsParser#TASK_ID_PATTERN}
 * @param fromStage the starting stage name, or {@code null} to start at the pipeline's first
 *     stage; validated here only as non-blank — validity against the loaded definition's stage
 *     names is task 7.3's concern (design D4)
 * @param interactiveMode which role(s), if any, {@code --interactive} swaps from the manifest-
 *     driven CLI adapters to the interactive console adapters (FR10, design D6); {@link
 *     InteractiveMode#NONE} when the flag is absent
 */
public record RunArguments(
        Path project,
        TaskSource taskSource,
        @Nullable String taskId,
        @Nullable String fromStage,
        InteractiveMode interactiveMode) {

    /**
     * The scope of {@code --interactive}'s override (FR10, design D6): the manifest-driven
     * default wires the real CLI adapters for every stage (all stages are {@code agent-cli};
     * {@code api} stages already fail startup validation, task 9.2) — {@code --interactive}
     * restores add-manual-run's fully-interactive behavior, and the scoped forms swap just one
     * role, leaving the other on its CLI adapter.
     */
    public enum InteractiveMode {
        /** {@code --interactive} absent: both roles use their manifest-driven CLI adapter. */
        NONE,
        /** Bare {@code --interactive}: both the executor and the judge are interactive. */
        ALL,
        /** {@code --interactive=executor}: only the stage executor is interactive. */
        EXECUTOR_ONLY,
        /** {@code --interactive=judge}: only the judge voter is interactive. */
        JUDGE_ONLY
    }
}
