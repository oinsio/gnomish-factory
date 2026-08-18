package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;

/**
 * The check-SPI view of the sandboxed-mode workspace: the round under verification is
 * identified by an attempt (snapshot) commit, and a check client learns which commit that is
 * by narrowing the {@link Workspace} the engine handed it to this type. Narrowing to a
 * workspace capability is the codebase's established idiom; publishing the target type here
 * is what lets a third-party external check know what it verifies with {@code
 * gnomish-plugin-api} as its only declared dependency (design D1).
 *
 * <p>The engine's own workspace record implements this interface. The mutable ref behind it —
 * one workspace instance lives for the whole run while the snapshot step re-records each
 * round's commit — stays an engine internal: plugins get read access to the sha of the
 * current round, never the recording protocol.
 *
 * <p>Implements FR1 of close-plugin-api-compilability-gap.
 */
@FunctionalInterface
public interface AttemptCommitWorkspace extends Workspace {

    /**
     * The current round's attempt commit sha.
     *
     * @return the sha of the snapshot commit under verification; never null or blank
     * @throws IllegalStateException if no snapshot was recorded yet — verifying without an
     *     attempt commit is a protocol violation by construction (FR1)
     */
    String attemptCommitSha();
}
