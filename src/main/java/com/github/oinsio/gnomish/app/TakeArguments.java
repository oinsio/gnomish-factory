package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed and first-tier-validated flags of one {@code gnomish take} invocation, produced by
 * {@link TakeArgumentsParser} (task 5.13). Mirrors {@link RunArguments}'s style, but {@code take}
 * has its own, narrower flag set (design D15): no {@code --mode} (always git mode), no ad-hoc task
 * source or {@code --resume} (the claim/branch protocol replaces it), no {@code --from-stage}
 * (design D4).
 *
 * <p>Implements FR9 of add-tracker-port.
 *
 * @param dir the target project directory; defaults to the current working directory when {@code
 *     --dir} is absent, matching {@link RunArguments#dir()}; unresolved, not checked for existence
 *     here
 * @param ref the raw positional ref string naming the task for explicit mode (e.g. {@code take
 *     42} or {@code take github:owner/repo#42}), or {@code null} for bare auto mode; carried
 *     verbatim — short-ref expansion (`42`, `#42`) into a canonical {@code TaskRef} is task 5.14's
 *     job, not this record's or its parser's
 * @param interactiveMode which role(s), if any, use the interactive console adapter (FR10 of
 *     add-manual-run, design D6), parsed with identical semantics to {@link
 *     RunArguments#interactiveMode()}
 * @param base the {@code --base} branch override for a fresh explicit-mode claim, or {@code null};
 *     rejected outright on the bare form (spec "Flag validation")
 * @param discardWork {@code --discard-work}: true discards an interrupted round's leftovers
 *     instead of salvaging them when resuming
 */
record TakeArguments(
        Path dir,
        @Nullable String ref,
        RunArguments.InteractiveMode interactiveMode,
        @Nullable String base,
        boolean discardWork) {}
