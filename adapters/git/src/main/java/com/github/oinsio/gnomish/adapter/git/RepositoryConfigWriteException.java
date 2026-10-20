package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown by {@link GitProcessRunner}'s untyped entry when the argv writes the repository's
 * configuration — {@code config} in any form but a read, or {@code remote add|rename|remove|rm|
 * set-head|set-branches|set-url}. The factory writes nothing to a clone's configuration: what a
 * factory git process needs is the invocation policy {@code GitInvocation} composes for every
 * invocation, and a persisted key is the escape hatch that policy exists to close (FR5, design D4
 * of own-git-invocation-policy). Thrown before any process is launched, so nothing is written.
 *
 * <p>A programming error, not an outcome: no caller catches it, and a spec that meets it has
 * written a configuration key through the runner instead of the fixture's own writer.
 *
 * <p>Implements FR5, NFR-O1 of own-git-invocation-policy.
 */
public final class RepositoryConfigWriteException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param subcommand the subcommand the argv named, {@code config} or {@code remote}
     */
    RepositoryConfigWriteException(String subcommand) {
        super("git " + subcommand + " in a write form changes the repository configuration, which the factory"
                + " never writes: carry the setting per invocation through GitInvocation (the one owner of the"
                + " factory's git configuration) instead");
    }
}
