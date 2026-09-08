package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The bare-commit half of the base refresh: a commit has no tip to refresh, so the only question is
 * whether the clone holds it. Present — the usual manual {@code --base <sha>} case — costs zero
 * network; absent, it is fetched by SHA and verified again, and no ref is written for it at all.
 *
 * <p>No private ref guards the fetched object: the task-creation commit references it seconds
 * later, far inside git's prune grace, and a ref would only be one more thing to clean up
 * ({@code docs/adr/0006-base-refresh-fetch.md}).
 *
 * <p>A remote that refuses fetch-by-SHA is a task-level park, not an outage: protocol v2 takes any
 * want and the public forges all serve it, so a refusal is a self-hosted server's configuration
 * ({@code uploadpack.allowAnySHA1InWant}) and no retry will change it. The refusal is told apart
 * from an outage by the same rule the rest of the refresh uses — the object is read back, and a
 * remote that answered but declined leaves the invocation exited rather than killed.
 *
 * <p>Implements FR6, FR8, FR9 of add-base-ref-resolution.
 */
final class CommitBaseFetch {

    /**
     * What may be a commit SHA rather than a ref name: hex, and at least git's own minimum
     * abbreviation. An abbreviation is accepted only from the clone's own object database — the
     * fetch below needs a full name — which is what keeps a manual {@code --base 1a2b3c4} working
     * offline while never sending a guess to a remote.
     */
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{7,64}");

    /** The two full object-name lengths git has: SHA-1 and SHA-256. Only these may be fetched. */
    private static final int SHA1_LENGTH = 40;

    private static final int SHA256_LENGTH = 64;

    private final GitProcessRunner runner;
    private final OriginProbe probe;

    CommitBaseFetch(GitProcessRunner runner) {
        this.runner = runner;
        this.probe = new OriginProbe(runner);
    }

    /**
     * Whether {@code ref} could name a commit object rather than a branch or a tag. A candidate
     * only: {@link #fetch} still falls back to the ref namespaces when the clone holds no such
     * object and the name is too short to ask origin about.
     */
    static boolean looksLikeCommit(String ref) {
        return HEX.matcher(ref).matches();
    }

    /**
     * Establishes that the clone holds {@code sha} as a commit, fetching it when it does not.
     *
     * @param cloneDir the factory clone; never null
     * @param sha the candidate commit name, already {@link #looksLikeCommit} accepted
     * @return the resolved full commit name, a refusal, or empty when {@code sha} is an
     *     abbreviation the clone does not hold — the caller then asks origin about it as a ref name
     */
    Optional<BaseRefreshOutcome> fetch(Path cloneDir, String sha) {
        Optional<String> present = commit(cloneDir, sha);
        if (present.isPresent()) {
            return Optional.of(new BaseRefreshOutcome.Refreshed(sha, present.get(), BaseRefKind.COMMIT));
        }
        if (sha.length() != SHA1_LENGTH && sha.length() != SHA256_LENGTH) {
            return Optional.empty();
        }
        GitCommandResult fetch = NarrowFetch.of(runner, cloneDir, sha);
        return Optional.of(commit(cloneDir, sha)
                .<BaseRefreshOutcome>map(
                        resolved -> new BaseRefreshOutcome.Refreshed(sha, resolved, BaseRefKind.COMMIT))
                .orElseGet(() -> unresolved(cloneDir, sha, fetch)));
    }

    /**
     * Which failure class a fetch that delivered no object belongs to. Unlike a branch or a tag,
     * whose presence on origin was already established by a refs read, a commit is asked for
     * blind — so git's non-zero exit alone cannot say whether the remote declined to serve it or
     * was never reachable in the first place. The probe asks that question directly rather than
     * reading it out of git's (localized, unstable) wording.
     */
    private BaseRefreshOutcome unresolved(Path cloneDir, String sha, GitCommandResult fetch) {
        if (fetch.termination() != Termination.EXITED || !probe.answers(cloneDir)) {
            return new BaseRefreshOutcome.Unavailable(fetch.failureDetail("commit fetch of " + sha));
        }
        return new BaseRefreshOutcome.Refused("The base commit " + sha + " is not in this clone and origin, which "
                + "is reachable, did not serve it: " + fetch.failureDetail("commit fetch") + ". A server on git "
                + "protocol v0 without 'uploadpack.allowAnySHA1InWant' refuses to serve a commit by name; name a "
                + "branch or a tag instead, or enable it on the server.");
    }

    /** The full commit name {@code revision} resolves to in this clone, or empty when it holds none. */
    private Optional<String> commit(Path cloneDir, String revision) {
        return VerifiedTip.read(runner.run(cloneDir, "rev-parse", "--verify", "--quiet", revision + "^{commit}"));
    }
}
