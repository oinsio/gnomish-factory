package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the {@code origin} remote URL of a clone (design D5 of add-serve-sandbox-lifecycle): the
 * input the sandbox's project-identity digest is derived from, so the sweep can scope itself to
 * this factory's own project without an explicit config key. Mirrors {@link BranchPush}'s and
 * {@link BestEffortPush}'s {@code remote get-url origin} presence check, but returns the URL
 * itself rather than only its existence.
 *
 * <p>Implements FR8 of add-serve-sandbox-lifecycle.
 */
public final class OriginRemoteUrl {

    private static final String REMOTE = "origin";

    private OriginRemoteUrl() {}

    /**
     * Reads the {@code origin} remote URL configured for {@code repo}.
     *
     * @param runner the git subprocess seam; never null
     * @param repo the clone to read {@code origin} from; never null
     * @return the trimmed origin URL, or empty when no {@code origin} remote is configured (a
     *     bare local checkout) or the command otherwise fails
     */
    public static Optional<String> read(GitProcessRunner runner, Path repo) {
        GitCommandResult result = runner.run(repo, "remote", "get-url", REMOTE);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String url = result.stdout().trim();
        return url.isEmpty() ? Optional.empty() : Optional.of(url);
    }
}
