package com.github.oinsio.gnomish.app.workspace.fake

import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace

/**
 * Builds the sandboxed-mode workspace a spec needs to hand an adapter: a fresh
 * {@link CurrentRound} closed by one attempt-commit sha ({@link ClosedRounds}), wrapped in the engine's
 * {@link RecordedAttemptCommitWorkspace}. Every spec that drives an attempt-commit-shaped
 * port assembles exactly this three-line pair, so it lives here once.
 *
 * <p>The declared return type is the published {@link AttemptCommitWorkspace} contract, not
 * the engine record: a spec in a module that compiles against {@code gnomish-plugin-api}
 * alone (the github vendor bundle) can then reach a real workspace without naming an
 * {@code :application} type, so {@code :application} stays an unreferenced transitive of
 * this fixture module on its test classpath (FR3, design D6 of
 * close-plugin-api-compilability-gap). A caller needing the record's {@code rounds()}
 * cell builds it locally rather than widening this signature back.
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
final class AttemptCommitWorkspaces {

    private AttemptCommitWorkspaces() {
    }

    /** A workspace carrying {@code sha} as the harvested attempt commit. */
    static AttemptCommitWorkspace at(String sha) {
        new RecordedAttemptCommitWorkspace(ClosedRounds.at(sha))
    }

    /** A workspace with no attempt commit recorded yet. */
    static AttemptCommitWorkspace empty() {
        new RecordedAttemptCommitWorkspace(new CurrentRound())
    }
}
