package com.github.oinsio.gnomish.app;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * A batch {@code take} run's closing summary (FR3, NFR-O2, UX3 of add-factory-serve, tracker-take
 * spec "Batch take works the list with a summary and one exit code"): a machine-findable,
 * one-line log naming every ref and its outcome, logged by {@link TakeCommand} once {@link
 * TakeDispatcher#runBatch} returns and before the aggregate exit code is thrown — "a batch run
 * reads like a checklist afterwards: every ref, its outcome" (UX3).
 *
 * <p>Mirrors {@link com.github.oinsio.gnomish.app.serve.DrainReport#summary()}'s wording so the
 * two sibling closing reports read alike; simpler than that class since a batch run's outcomes are
 * a fixed, already-complete list by the time this renders — no concurrent-write sink is needed.
 *
 * <p>Implements FR3, NFR-O2, UX3 of add-factory-serve.
 */
final class TakeBatchSummary {

    private TakeBatchSummary() {}

    /**
     * Renders {@code outcomes} into one summary line and logs it at INFO.
     *
     * @param outcomes every ref's terminal outcome from one batch run, in CLI order; never empty
     * @param log the logger to write the summary line to; never null
     */
    static void log(List<TakeBatchOutcome> outcomes, Logger log) {
        log.info(render(outcomes));
    }

    /**
     * Returns one line naming every ref and its outcome, e.g. {@code "batch take: 2 ref(s) — 42 ->
     * delivered: shipped it, 43 -> skipped: already done"}.
     *
     * @param outcomes every ref's terminal outcome from one batch run, in CLI order; never empty
     */
    static String render(List<TakeBatchOutcome> outcomes) {
        String named = outcomes.stream()
                .map(outcome -> outcome.ref() + " -> " + outcome.describe())
                .collect(Collectors.joining(", "));
        return "batch take: " + outcomes.size() + " ref(s) — " + named;
    }
}
