package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * Makes an existing, empty directory an empty git repository, through the factory's git runner and
 * so under the factory's invocation policy (FR12 of define-executor-contract, design D19): the
 * scratch working copy of one conformance case, which the working-copy check of a {@code verdict}
 * or {@code decision} run reads through git and fails closed without. Nothing is committed and no
 * configuration is written.
 *
 * <p>Public so the composition root can construct it over its one runner; the role it takes stays
 * package-private ({@link GitVersionCheck} has the same shape).
 *
 * <p>Implements FR12 of define-executor-contract.
 */
public final class RepositoryInit {

    private final GitRunner runner;

    /** @param runner the factory's git runner; never null */
    public RepositoryInit(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Runs {@code git init} in {@code directory}.
     *
     * @param directory an existing directory; never null
     * @throws IllegalStateException naming how git ended, when it did not initialise the repository
     */
    public void init(Path directory) {
        GitCommandResult result = runner.run(directory, "init", "--quiet");
        if (!result.succeeded()) {
            throw new IllegalStateException("could not make " + directory + " a git repository: "
                    + result.failureDetail("git init").forLog());
        }
    }
}
