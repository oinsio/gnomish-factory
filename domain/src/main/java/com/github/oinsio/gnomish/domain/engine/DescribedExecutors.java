package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.Map;

/**
 * What the describe preflight obtained for this run, as the engine receives it (FR13 of
 * define-executor-contract, design D5, D11): {@code all} is the describe version of every executor
 * the preflight described, which the engine pre-flight compares with the task's pin before any round.
 *
 * <p>Which executor a stage's rounds run is the law's to say, not this value's: a stage names its
 * executor declaration by {@code executor.name} (task 6.1 of define-executor-contract), so the one
 * owner of "which executor ran this stage" derives it from the stage and this value together —
 * {@link #ranBy} for what the round writer pins, {@link #executorOf} for what the round's log and
 * ledger lines name — and the two cannot disagree. A stage that names no executor, or names one the
 * preflight did not describe, runs no described executor.
 *
 * <p>A run-held value with one producer, the describe preflight (task 6.2 of define-executor-contract
 * fills {@code all}); until then every run holds {@link #none()}, so nothing is pinned and nothing is
 * compared — the behaviour before the pin existed. Inert value data compared by content.
 *
 * <p>Implements FR13, NFR-O2, NFR-S1, UX3 of define-executor-contract.
 *
 * <p>{@code credentials} rides with the versions because it has the same one producer and the same
 * one run: the credential names each described {@code program} executor's describe declares, which
 * the preflight has shown equal to the law's grant (NFR-S1, task 6.4 of define-executor-contract).
 * The engine reads no credential; the {@code program} exec's wiring does, to build that child's own
 * resolved layer.
 *
 * @param all the describe version per described executor name; never null
 * @param credentials the granted credential names per described executor name; never null
 */
public record DescribedExecutors(ExecutorVersions all, ExecutorCredentialNames credentials) {

    private static final DescribedExecutors NONE =
            new DescribedExecutors(ExecutorVersions.none(), ExecutorCredentialNames.none());

    /** No executor described: a run with no {@code program} executor, or one before the preflight exists. */
    public static DescribedExecutors none() {
        return NONE;
    }

    /**
     * The describe version of the executor {@code stage} runs: what its round writer pins.
     *
     * @param stage the stage; never null
     * @return the named executor's version under its name; {@link ExecutorVersions#none()} for a
     *     stage that names no executor or one the preflight did not describe
     */
    public ExecutorVersions ranBy(StageDefinition stage) {
        var name = stage.executor().name();
        if (name == null) {
            return ExecutorVersions.none();
        }
        return all.of(name)
                .map(version -> new ExecutorVersions(Map.of(name, version)))
                .orElse(ExecutorVersions.none());
    }

    /**
     * The executor that ran a round of {@code stage} (NFR-O2, UX3 of define-executor-contract): the
     * stage's {@code executor.name} with its describe version when the preflight described it,
     * without one when it did not; a stage that names no executor is named by its executor type word
     * ({@code agent-cli}, {@code api} — the law's own spelling of the type), with no version.
     *
     * @param stage the stage; never null
     * @return the round's executor; never null
     */
    public RoundExecutor executorOf(StageDefinition stage) {
        var name = stage.executor().name();
        if (name == null) {
            return new RoundExecutor(Participants.typeWord(stage.executor().type()), null);
        }
        return new RoundExecutor(name, all.of(name).orElse(null));
    }
}
