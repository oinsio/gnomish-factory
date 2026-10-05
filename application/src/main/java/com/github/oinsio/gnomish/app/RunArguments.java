package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed and first-tier-validated flags of one {@code gnomish run} invocation, produced by
 * {@link RunArgumentsParser} (design D5). This is a pure value: it carries no filesystem or
 * pipeline knowledge beyond what {@link RunArgumentsParser} itself checks — {@code dir}'s
 * existence and {@code fromStage}'s validity against a loaded {@code PipelineDefinition} are
 * later tasks' concern (7.2/7.3, design D3/D4).
 *
 * <p>Implements FR1 of add-manual-run; {@code dir} (renamed from {@code project}) and
 * {@code mode} implement FR7, design D8 of add-git-workflow; {@code base}, {@code resume},
 * {@code discardWork} implement FR7, FR8, FR10, design D7, D9, D10 of add-git-workflow.
 *
 * @param dir the target project directory; defaults to the current working directory when
 *     {@code --dir} is absent (design D3); absolute and normalized (FR7 of
 *     fix-operator-blockers), not checked for existence here
 * @param taskSource the task description's origin, exactly one of inline text or a file path
 *     (FR1); {@code null} when {@code --resume} is present — resume reads task identity from the
 *     branch, not from a freshly supplied description (FR8)
 * @param taskId the operator-supplied task id override, or {@code null} to let task synthesis
 *     (task 7.3, FR2) generate one; when present, validated against
 *     {@link RunArgumentsParser#TASK_ID_PATTERN}
 * @param fromStage the starting stage name, or {@code null} to start at the pipeline's first
 *     stage; validated here only as non-blank — validity against the loaded definition's stage
 *     names is task 7.3's concern (design D4)
 * @param mode {@code git} (default) or {@code in-place} (FR7, design D8 of add-git-workflow);
 *     git mode never mutates {@code dir}, in-place is the preserved add-manual-run behavior
 * @param base the branch base override, or {@code null} to use the current clone state (FR7,
 *     design D7); git-only, mutually exclusive with {@code --mode=in-place}
 * @param resume the task id to resume by branch, or {@code null} for a fresh run (FR8, design
 *     D9); git-only, mutually exclusive with {@code --mode=in-place} and with
 *     {@code --task}/{@code --task-file}/{@code --task-id}/{@code --from-stage}
 * @param discardWork whether to reset to the last recorded round instead of salvaging an
 *     interrupted round's uncommitted work (FR10, design D10); git-only, mutually exclusive with
 *     {@code --mode=in-place}, meaningful only together with {@code --resume}
 */
public record RunArguments(
        Path dir,
        @Nullable TaskSource taskSource,
        @Nullable String taskId,
        @Nullable String fromStage,
        Mode mode,
        @Nullable String base,
        @Nullable String resume,
        boolean discardWork) {

    public RunArguments {
        ArgumentsParsingSupport.requireAbsoluteDir(dir);
    }

    /**
     * The {@code --mode} flag's value (FR7, design D8 of add-git-workflow): which run behavior
     * governs {@code dir} and task persistence.
     */
    public enum Mode {
        /** Default: {@code dir} is a project clone; branch/worktree/commits/pushes, resumable. */
        GIT,
        /** The preserved add-manual-run behavior: no git, in-memory state, no resume. */
        IN_PLACE
    }
}
