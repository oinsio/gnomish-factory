package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The one construction site of a base refresh's {@code git fetch} argv — the flag set design D11
 * and {@code docs/adr/0006-base-refresh-fetch.md} fix, in one place so no kind can quietly acquire
 * a different one.
 *
 * <p>Every flag answers a way git would otherwise move a ref the factory did not name:
 *
 * <ul>
 *   <li>{@code --no-tags}: tag auto-following writes into {@code refs/tags/} even on a fetch by
 *       SHA. Narrow means exactly one ref, no auto-followed extras.
 *   <li>{@code --refmap=}: a fetch with an explicit destination <em>still</em> applies the
 *       configured {@code remote.origin.fetch} refspecs as a destination mapping; only an empty
 *       refmap disables that, so a clone with a non-standard configuration cannot make this fetch
 *       move refs of its own choosing.
 *   <li>{@code --no-write-fetch-head}: {@code FETCH_HEAD} is one unlocked file per clone,
 *       truncated by every fetch. The factory never reads it, and does not write it either.
 * </ul>
 *
 * <p>No {@code --depth}: full history, because resume must resolve it. Serialization is
 * {@link CloneMutationLock}, which {@link GitProcessRunner} applies to every fetch, so git's own
 * compare-and-swap ref failure between concurrent slots is unreachable here.
 *
 * <p>Implements FR6 of add-base-ref-resolution.
 */
final class NarrowFetch {

    private NarrowFetch() {}

    /**
     * Fetches exactly {@code refspec} from {@code origin}.
     *
     * @param runner the git subprocess seam; never null
     * @param cloneDir the factory clone the fetch runs in; never null
     * @param refspec one source:destination refspec, or a bare commit SHA
     * @return the invocation's outcome, whose exit code is never the authority on its own — every
     *     caller reads the destination ref or the object back afterwards
     */
    static GitCommandResult of(GitProcessRunner runner, Path cloneDir, String refspec) {
        return runner.run(
                cloneDir, "fetch", "--no-tags", "--no-write-fetch-head", "--refmap=", OriginRemote.NAME, refspec);
    }
}
