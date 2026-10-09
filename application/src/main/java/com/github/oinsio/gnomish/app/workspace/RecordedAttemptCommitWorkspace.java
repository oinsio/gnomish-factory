package com.github.oinsio.gnomish.app.workspace;

import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;

/**
 * The sandboxed-mode workspace the engine hands to check runners: instead of a host
 * filesystem path (the working copy is a private detail of the task environment), it
 * carries the run's {@link CurrentRound}, whose closed round names the harvested attempt
 * (snapshot) commit of the round under verification (design D15; design D10 of
 * make-checkpoint-gate-durable). Check runners downcast to it the way host
 * runners downcast to {@link DirectoryWorkspace}: builtin checks read the commit as bare
 * git objects in the factory clone, the pin-check guard byte-compares pinned definition
 * files at it, external-check adapters poll platform runs of exactly that commit, and
 * fresh-box checks and judge votes materialize environments from it.
 *
 * <p>The cell — not a frozen sha — is deliberate: one workspace instance lives for the
 * whole run while each round's snapshot is recorded into it ({@link CurrentRound#snapshotted},
 * or {@link CurrentRound#restore} on resume), so every consumer always observes the current
 * round's attempt commit.
 *
 * <p>The name is decorated because the plugin-facing type owns the undecorated one: this record
 * is the engine's <em>recording</em> implementation of the published {@link
 * AttemptCommitWorkspace} contract, whose sha a check plugin reads by narrowing to the api
 * interface (design D1/D2). The record itself, and the mutable cell protocol it carries, stay
 * engine internals — no plugin sees them.
 *
 * <p>Implements FR21, FR26 of add-sandbox-core; FR1 of close-plugin-api-compilability-gap; FR13 of
 * make-checkpoint-gate-durable.
 *
 * @param rounds the run's round cell; its closed round carries the current attempt commit
 */
public record RecordedAttemptCommitWorkspace(CurrentRound rounds) implements AttemptCommitWorkspace {

    /**
     * The current round's attempt commit sha, as promised by {@link AttemptCommitWorkspace}.
     *
     * @throws IllegalStateException if the current round is not closed by a snapshot yet —
     *     verifying without an attempt commit is a protocol violation by construction (D15)
     */
    @Override
    public String attemptCommitSha() {
        return rounds.closed().attemptCommit();
    }
}
