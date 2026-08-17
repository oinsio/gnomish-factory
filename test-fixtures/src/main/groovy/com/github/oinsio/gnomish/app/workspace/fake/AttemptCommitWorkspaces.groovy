package com.github.oinsio.gnomish.app.workspace.fake

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace

/**
 * Builds the sandboxed-mode {@link AttemptCommitWorkspace} a spec needs to hand an
 * adapter: a fresh {@link AttemptCommitRef} with one recorded attempt-commit sha.
 * Every spec that drives an attempt-commit-shaped port assembles exactly this
 * three-line pair, so it lives here once.
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
final class AttemptCommitWorkspaces {

    private AttemptCommitWorkspaces() {
    }

    /** A workspace carrying {@code sha} as the harvested attempt commit. */
    static AttemptCommitWorkspace at(String sha) {
        def ref = new AttemptCommitRef()
        ref.record(sha)
        new AttemptCommitWorkspace(ref)
    }

    /** A workspace with no attempt commit recorded yet. */
    static AttemptCommitWorkspace empty() {
        new AttemptCommitWorkspace(new AttemptCommitRef())
    }
}
