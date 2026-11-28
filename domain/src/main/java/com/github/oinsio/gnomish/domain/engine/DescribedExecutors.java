package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;
import java.util.Set;

/**
 * What the describe preflight obtained for this run, as the engine receives it (FR13 of
 * define-executor-contract, design D5, D11): {@code all} is the describe version of every executor
 * the preflight described, which the engine pre-flight compares with the task's pin before any round.
 *
 * <p>Which executor a stage's rounds run is the law's to say, not this value's: a block resolves to
 * the registry entry {@link StageDefinition.Executor#registryName()} names — {@code claude} for an
 * {@code agent-cli} block, the declaration for a {@code program} block — the name dispatch resolves
 * (design D12 of extract-agent-round-runner, "The name in the record"). This is the one owner of "who
 * ran this, at which version": {@link #ranBy} for what the round writer pins, {@link #executorOf} for
 * what a round's or a check's log and ledger lines and participant entries name, and {@link #polls},
 * all naming a block through one private function, so no record names what dispatch did not run. A
 * built-in answers {@link BuiltInExecutors#VERSION} and is never pinned; a program the preflight did
 * not describe answers no version.
 *
 * <p>A run-held value with one producer, the describe preflight (task 6.2 of define-executor-contract
 * fills {@code all}). A run whose law declares no {@code program} executor is described as empty,
 * equal by content to {@link #none()}, so nothing is pinned and nothing is compared; {@link #none()}
 * itself serves the engine assembled with no preflight ({@code EnginePorts}' in-place constructor).
 * Inert value data compared by content.
 *
 * <p>Implements FR13, NFR-O2, NFR-S1, UX3 of define-executor-contract; NFR-O1, FR14 of
 * extract-agent-round-runner.
 *
 * <p>{@code credentials} rides with the versions because it has the same one producer and the same
 * one run: the credential names each described {@code program} executor's describe declares, which
 * the preflight has shown equal to the law's grant (NFR-S1, task 6.4 of define-executor-contract).
 * The engine reads no credential; the {@code program} exec's wiring does, to build that child's own
 * resolved layer.
 *
 * <p>{@code pollable} rides with them for the same reason: the names of the described executors whose
 * describe declares {@code poll}, the one fact the engine reads to decide whether a {@code transform}
 * round's {@code pending} is handed to its pending poll or refused as unsupported (design D11 of
 * extract-agent-round-runner; wire-contract "Closed status taxonomy"). An executor the preflight did
 * not describe — the agent entry, a built-in — declares nothing, so none of its rounds is polled.
 *
 * @param all the describe version per described executor name; never null
 * @param credentials the granted credential names per described executor name; never null
 * @param pollable the names of the described executors whose describe declares {@code poll}; copied,
 *     unmodifiable, possibly empty
 */
public record DescribedExecutors(ExecutorVersions all, ExecutorCredentialNames credentials, Set<String> pollable) {

    private static final UntrustedText BUILT_IN_VERSION = UntrustedText.factory(BuiltInExecutors.VERSION);

    private static final DescribedExecutors NONE =
            new DescribedExecutors(ExecutorVersions.none(), ExecutorCredentialNames.none(), Set.of());

    public DescribedExecutors {
        pollable = Set.copyOf(pollable);
    }

    /** No executor described: a run with no {@code program} executor, or an engine assembled with no preflight. */
    public static DescribedExecutors none() {
        return NONE;
    }

    /**
     * The describe version of the executor {@code stage} runs: what its round writer pins. A built-in
     * is never pinned (design D12 of extract-agent-round-runner, "The version of a built-in"), whatever
     * {@code all} holds: a pinned {@code built-in} would make the release that gives the entry a real
     * version a pipeline mismatch for every task in flight, so a task that ran {@code claude} holds
     * no pin for it.
     *
     * @param stage the stage; never null
     * @return the named executor's version under its name; {@link ExecutorVersions#none()} for a
     *     built-in or a program the preflight did not describe
     */
    public ExecutorVersions ranBy(StageDefinition stage) {
        var name = nameOf(stage);
        if (BuiltInExecutors.isBuiltIn(name)) {
            return ExecutorVersions.none();
        }
        return all.of(name)
                .map(version -> new ExecutorVersions(Map.of(name, version)))
                .orElse(ExecutorVersions.none());
    }

    /**
     * The executor that ran a round of {@code stage} (NFR-O2, UX3 of define-executor-contract; NFR-O1
     * of extract-agent-round-runner): the registry entry its block resolved to, the name dispatch
     * resolved, with that entry's version.
     *
     * @param stage the stage; never null
     * @return the round's executor; never null
     */
    public RoundExecutor executorOf(StageDefinition stage) {
        return named(nameOf(stage));
    }

    /**
     * The executor an executor check's rounds ran (NFR-O1 of extract-agent-round-runner): the entry
     * the check names — for a {@code judge} check the one its block resolved to ({@link
     * VerifyCheck.Judge#asExecutorCheck()}) — with that entry's version, as a round's line carries it.
     *
     * @param check the executor check; never null
     * @return the check's executor; never null
     */
    public RoundExecutor executorOf(VerifyCheck.Executor check) {
        return named(check.executorName());
    }

    /**
     * Whether a {@code pending} of {@code stage}'s rounds is polled (design D11 of
     * extract-agent-round-runner): the stage's executor is a program the preflight described as
     * declaring {@code poll}. A built-in, or a program not described, is never polled.
     *
     * <p>Implements FR10 of extract-agent-round-runner.
     *
     * @param stage the stage; never null
     * @return true exactly when the stage's executor declares {@code poll}
     */
    public boolean polls(StageDefinition stage) {
        return pollable.contains(nameOf(stage));
    }

    /** The one naming of a block: the registry entry dispatch resolves it to (design D12). */
    private static String nameOf(StageDefinition stage) {
        return stage.executor().registryName();
    }

    /**
     * An entry with its version: {@link BuiltInExecutors#VERSION} for a built-in, the described
     * version for a program, none for a program the run did not describe.
     */
    private RoundExecutor named(String name) {
        if (BuiltInExecutors.isBuiltIn(name)) {
            return new RoundExecutor(name, BUILT_IN_VERSION);
        }
        return new RoundExecutor(name, all.of(name).orElse(null));
    }
}
