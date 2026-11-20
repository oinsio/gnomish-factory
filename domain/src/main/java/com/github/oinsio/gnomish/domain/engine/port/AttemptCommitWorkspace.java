package com.github.oinsio.gnomish.domain.engine.port;

/**
 * The sandboxed-mode workspace capability that names the round under verification: an attempt
 * (snapshot) commit. A component learns which commit that is by narrowing the {@link Workspace} it
 * was handed to this type — narrowing to a workspace capability is the codebase's established idiom.
 * The SCM verdict built-ins read the commit they verify this way (design D18 of
 * migrate-verify-to-executor-contract); a verdict program reads it as {@code HEAD} of the working
 * copy its request names and needs no Java type at all.
 *
 * <p>The engine's own workspace record implements this interface. The mutable ref behind it — one
 * workspace instance lives for the whole run while the snapshot step re-records each round's commit
 * — stays an engine internal: a reader gets the sha of the current round, never the recording
 * protocol.
 *
 * <p>Implements FR1 of close-plugin-api-compilability-gap; moved out of {@code gnomish-plugin-api}
 * by task 6.1 of migrate-verify-to-executor-contract (D18, D20).
 */
@FunctionalInterface
public interface AttemptCommitWorkspace extends Workspace {

    /**
     * The current round's attempt commit sha.
     *
     * @return the sha of the snapshot commit under verification; never null or blank
     * @throws IllegalStateException if no snapshot was recorded yet — verifying without an attempt
     *     commit is a protocol violation by construction (FR1)
     */
    String attemptCommitSha();
}
