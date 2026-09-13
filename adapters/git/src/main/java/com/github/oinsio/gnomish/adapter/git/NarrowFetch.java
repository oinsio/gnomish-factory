package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The one construction site of a factory {@code git fetch} argv — the flag set design D11 and
 * {@code docs/adr/0006-base-refresh-fetch.md} fix, in one place so no caller can quietly acquire a
 * different one. Its callers are the base refresh (one per base kind) and {@link
 * TaskBranchLocator}'s resume/inspection locate; both fetch exactly one named ref into exactly one
 * named destination, which is the whole reason one argv serves them.
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
 *   <li>{@code --end-of-options}: {@code fetch} parses options <em>after</em> the remote name too
 *       — {@code fetch origin --dry-run <refspec>} is honoured as a dry run — so a refspec whose
 *       first character is {@code -} would be read as a flag rather than as a ref. Every caller
 *       here prefixes its refspec ({@code +refs/heads/}, {@code refs/tags/}, a hex object name),
 *       and {@code RefNameSyntax} refuses a leading {@code -} besides; the separator is what makes
 *       that structural rather than a property of the current callers. Not {@code --}, which git
 *       reads as the start of a pathspec.
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
     * @param refspec one source:destination refspec, or a bare commit SHA. The source may be a
     *     bare branch name ({@link TaskBranchLocator}): origin resolves it, and the explicit
     *     destination is what keeps the answer out of every other ref this clone holds
     * @return the invocation's outcome, whose exit code is never the authority on its own — every
     *     caller reads the destination ref or the object back afterwards
     */
    static GitCommandResult of(GitProcessRunner runner, Path cloneDir, String refspec) {
        return runner.run(
                cloneDir,
                "fetch",
                "--no-tags",
                "--no-write-fetch-head",
                "--refmap=",
                "--end-of-options",
                OriginRemote.NAME,
                refspec);
    }
}
