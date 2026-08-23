package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The single reader of the {@code origin} remote a clone is configured with (design D2 of
 * fix-lifecycle-push): the {@code remote get-url origin} command exists here once, and both
 * questions asked of it — "is a remote configured at all?" (the precondition every best-effort
 * push checks before attempting anything) and "what is its URL?" (the input the sandbox's
 * project-identity digest is derived from, FR8/D5 of add-serve-sandbox-lifecycle) — are answered
 * from it. Absorbs the former {@code OriginRemoteUrl} plus the three inline {@code originConfigured}
 * copies in {@link BestEffortPush}, {@link BranchPush} and {@link RemoteAttemptDelivery}.
 *
 * <p>Neither reader ever throws: an unconfigured remote is a normal outcome of a purely local run.
 *
 * <p>Implements FR1 of fix-lifecycle-push; FR8 of add-serve-sandbox-lifecycle.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
public final class OriginRemote {

    /** The remote name the factory pushes to and reads from; the factory never uses another. */
    static final String NAME = "origin";

    private final GitProcessRunner runner;

    /**
     * @param runner the git subprocess seam; never null
     */
    public OriginRemote(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Reads the {@code origin} remote URL configured for {@code repo}.
     *
     * @param repo the clone to read {@code origin} from; never null
     * @return the trimmed origin URL, or empty when no {@code origin} remote is configured (a bare
     *     local checkout) or the command otherwise fails
     */
    public Optional<String> url(Path repo) {
        GitCommandResult result = getUrl(repo);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String url = result.stdout().trim();
        return url.isEmpty() ? Optional.empty() : Optional.of(url);
    }

    /**
     * The push precondition: whether {@code repo} has an {@code origin} remote at all. A run in a
     * clone without one is purely local, and every push point above this is a silent no-op.
     *
     * @param repo the clone to check; never null
     * @return {@code true} iff {@code git remote get-url origin} succeeds
     */
    boolean isConfigured(Path repo) {
        return getUrl(repo).exitCode() == 0;
    }

    private GitCommandResult getUrl(Path repo) {
        return runner.run(repo, "remote", "get-url", NAME);
    }
}
