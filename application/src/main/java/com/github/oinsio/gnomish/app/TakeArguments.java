package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The parsed and first-tier-validated flags of one {@code gnomish take} invocation, produced by
 * {@link TakeArgumentsParser} (task 5.13). Mirrors {@link RunArguments}'s style, but {@code take}
 * has its own, narrower flag set (design D15): no {@code --mode} (always git mode), no ad-hoc task
 * source or {@code --resume} (the claim/branch protocol replaces it), no {@code --from-stage}
 * (design D4).
 *
 * <p>Implements FR9 of add-tracker-port; FR2, FR3 of add-factory-serve.
 *
 * @param dir the target project directory; defaults to the current working directory when {@code
 *     --dir} is absent, matching {@link RunArguments#dir()}; unresolved, not checked for existence
 *     here
 * @param refs the raw positional ref strings naming the task(s) (e.g. {@code take 42} or {@code
 *     take github:owner/repo#42}): empty for bare auto mode, one element for explicit mode, two or
 *     more for batch mode (FR2 of add-factory-serve). Carried verbatim — short-ref expansion
 *     (`42`, `#42`) into a canonical {@code TaskRef} is task 5.14's job, not this record's or its
 *     parser's
 * @param interactiveMode which role(s), if any, use the interactive console adapter (FR10 of
 *     add-manual-run, design D6), parsed with identical semantics to {@link
 *     RunArguments#interactiveMode()}; rejected outright on batch mode (FR3 of add-factory-serve)
 * @param base the {@code --base} branch override for a fresh explicit-mode claim, or {@code null};
 *     rejected outright on the bare form and on batch mode (spec "Flag validation"; FR3 of
 *     add-factory-serve)
 * @param discardWork {@code --discard-work}: true discards an interrupted round's leftovers
 *     instead of salvaging them when resuming
 * @param takeover {@code --takeover}: the headless authorization to take over a {@code Working}
 *     task held by another instance without a TTY prompt (task 6.2 of add-claim-heartbeat, FR6);
 *     meaningful only for explicit-mode {@code take <ref>}, rejected on the bare form like {@code
 *     --base}
 */
record TakeArguments(
        Path dir,
        List<String> refs,
        RunArguments.InteractiveMode interactiveMode,
        @Nullable String base,
        boolean discardWork,
        boolean takeover) {

    // PIT M5 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // instance method sits on a record, and its mutants hit the same known JVMTI RedefineClasses
    // limitation as the record-constructor helpers build.gradle already documents (hcoles/pitest#1285).
    // Fully covered by TakeArgumentsParserSpec's bare-vs-explicit-mode scenarios.
    /**
     * Convenience accessor for the single-ref forms: the first (and only) ref in explicit mode,
     * or {@code null} in bare mode. Not meaningful for batch mode (2+ refs) — callers branch on
     * {@link #refs()} directly there.
     */
    @Nullable
    @DoNotMutate
    String ref() {
        return refs.isEmpty() ? null : refs.getFirst();
    }
}
