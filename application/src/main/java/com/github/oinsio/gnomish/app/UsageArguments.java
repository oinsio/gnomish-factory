package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The parsed flags of one {@code gnomish usage} invocation, produced by {@link
 * UsageArgumentsParser} (task 5.6). Unlike {@link StatusArguments}, {@code task} is required —
 * {@code gnomish usage} has no list mode.
 *
 * <p>Implements FR14 of add-git-workflow.
 *
 * @param dir the target project clone directory (the {@code --dir} value), absolute and
 *     normalized (FR7 of fix-operator-blockers); not checked for existence here
 * @param task the task id to reconstruct usage for
 * @param rendering the report's rendering: text by default, {@code --json} or {@code --markdown}
 *     (FR20 of define-executor-contract)
 */
record UsageArguments(Path dir, String task, Rendering rendering) {

    /** How the report is rendered: one per invocation, so the two flags cannot both win. */
    enum Rendering {
        /** The stage/round table with the snapshot, for the operator's terminal. */
        TEXT,
        /** The {@code --json} mini-contract, for a parser. */
        JSON,
        /** The {@code --markdown} report, for a pull request comment. */
        MARKDOWN
    }

    UsageArguments {
        ArgumentsParsingSupport.requireAbsoluteDir(dir);
    }
}
