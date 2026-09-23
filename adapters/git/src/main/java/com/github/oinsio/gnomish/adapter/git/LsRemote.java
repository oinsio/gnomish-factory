package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The single construction site of the factory's remote-refs read (design D2 of fix-lifecycle-push):
 * {@code git ls-remote} against {@code origin}, in the two shapes the factory asks it in.
 *
 * <p>The primitive lives here rather than inside any one of its callers because the factory now
 * puts four different questions to the same command — what tip {@code origin} holds for a task
 * branch ({@link RemoteBranchTip}), which branch it calls its default ({@link RemoteDefaultBranch}),
 * which namespace it holds a base name in ({@link RemoteBaseRef}), and whether it answers at all
 * ({@link OriginProbe}). Those are four responsibilities; the argv is one, and it is the argv that
 * the single-site rule is about — these are string arguments to a subprocess, invisible to
 * bytecode-level analysis, and the duplication that rule exists to prevent is a second spelling of
 * the command, not a second question asked through it.
 *
 * <p>Both shapes put {@code --end-of-options} before the remote name. {@code ls-remote} happens to
 * stop parsing options at the first non-option argument today, so a ref pattern beginning with
 * {@code -} is already read as a pattern rather than as a flag — but that is git's current
 * positional behaviour, not a documented guarantee, and the transfer owner ({@code GitTransfer}
 * in {@code :gittransfer}) shows the other posture is real ({@code fetch} does honour options
 * after the remote). The separator states the boundary instead of inheriting it. Not {@code --},
 * which git reads as the start of a pathspec.
 *
 * <p>Implements FR3 of fix-lifecycle-push; FR5, FR6 of add-base-ref-resolution.
 */
final class LsRemote {

    private LsRemote() {}

    /**
     * Reads the refs {@code origin} holds matching {@code refPatterns}.
     *
     * @param runner the git subprocess seam; never null
     * @param repo the clone the read runs from; never null
     * @param refPatterns the ref patterns to ask about; at least one
     * @return the invocation's outcome — {@code <sha>\t<ref>} lines on a zero exit
     */
    static GitCommandResult refs(GitProcessRunner runner, Path repo, String... refPatterns) {
        String[] argv = new String[refPatterns.length + 3];
        argv[0] = "ls-remote";
        argv[1] = "--end-of-options";
        argv[2] = OriginRemote.NAME;
        System.arraycopy(refPatterns, 0, argv, 3, refPatterns.length);
        return runner.run(repo, argv);
    }

    /**
     * Reads what {@code origin}'s own {@code HEAD} points at, symbolically — the only way to learn a
     * repository's default branch from the remote rather than from a clone's stale local copy.
     *
     * @param runner the git subprocess seam; never null
     * @param repo the clone the read runs from; never null
     * @return the invocation's outcome, whose first line is {@code "ref: <ref>\tHEAD"} when origin
     *     names one
     */
    static GitCommandResult symrefHead(GitProcessRunner runner, Path repo) {
        return runner.run(repo, "ls-remote", "--symref", "--end-of-options", OriginRemote.NAME, "HEAD");
    }
}
