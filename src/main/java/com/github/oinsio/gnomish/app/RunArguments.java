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
 */
public record RunArguments(
        Path project,
        TaskSource taskSource,
        @Nullable String taskId,
        @Nullable String fromStage) {}
