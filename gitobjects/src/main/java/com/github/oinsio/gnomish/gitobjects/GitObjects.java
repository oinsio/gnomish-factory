package com.github.oinsio.gnomish.gitobjects;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Facade for reading and writing git objects over a bare repository — no working copy, no checkout,
 * no hooks (design D19). {@link #resolveRef} peels a ref to its commit, {@link #readBlob} reads a
 * file's bytes at a commit under a size cap, {@link #historyContains} asks whether a message is
 * reachable, and {@link #commit} builds a plumbing commit and
 * advances a ref with an atomic compare-and-swap. Deterministic by construction: the caller supplies
 * all commit metadata, so fixed inputs yield a fixed commit id.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public final class GitObjects {

    private final GitExec exec;
    private final CommitBuilder commitBuilder;

    private GitObjects(GitExec exec, Path tempDir) {
        this.exec = exec;
        this.commitBuilder = new CommitBuilder(exec, tempDir);
    }

    /** Opens the library against a factory-owned git dir, using {@code tempDir} for private indexes. */
    public static GitObjects open(Path gitDir, Path tempDir) {
        return open(gitDir, tempDir, "git");
    }

    /** Test seam: as {@link #open(Path, Path)} but with an explicit git binary. */
    public static GitObjects open(Path gitDir, Path tempDir, String gitBinary) {
        return new GitObjects(new GitExec(gitDir, gitBinary), tempDir);
    }

    /** Resolves {@code ref} to the commit it points at, or empty if the ref does not exist. */
    public Optional<ObjectId> resolveRef(String ref) {
        GitExec.Result result = exec.run(List.of("rev-parse", "--verify", "--quiet", ref + "^{commit}"));
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        return Optional.of(ObjectId.of(result.stdoutText()));
    }

    /**
     * Reads the blob at {@code path} in {@code commit}'s tree, up to {@code sizeCap} bytes. Throws
     * {@link MissingObjectException} if the path is not a blob in that commit and {@link
     * BlobTooLargeException} if it exceeds the cap — never a silent truncation of factory state.
     */
    public byte[] readBlob(ObjectId commit, String path, long sizeCap) {
        TreePaths.validate(path);
        if (sizeCap <= 0) {
            throw new IllegalArgumentException("sizeCap must be positive: " + sizeCap);
        }
        GitExec.Result result =
                exec.run(List.of("cat-file", "blob", commit.hex() + ":" + path), null, Map.of(), sizeCap);
        if (result.exitCode() != 0) {
            throw new MissingObjectException("no blob at '" + path + "' in " + commit.hex() + ": "
                    + result.stderr().strip());
        }
        if (result.truncated()) {
            throw new BlobTooLargeException("blob at '" + path + "' exceeds " + sizeCap + " bytes");
        }
        return result.stdout();
    }

    /**
     * True when {@code path} exists in {@code commit}'s tree — as a blob or a tree — answered
     * from bare objects via {@code cat-file -e}, no checkout (the sandboxed leg of builtin
     * existence checks, FR21). Throws {@link InvalidTreePathException} for a path that is
     * absolute, escapes, or touches {@code .git}.
     */
    public boolean exists(ObjectId commit, String path) {
        TreePaths.validate(path);
        return exec.run(List.of("cat-file", "-e", commit.hex() + ":" + path)).exitCode() == 0;
    }

    /**
     * True when a commit whose message carries {@code messageFragment} verbatim is reachable from
     * {@code commit} — the bare-objects answer to a history question a working copy would answer
     * with {@code git log}. The fragment is matched as a fixed string, never as a pattern, and the
     * walk stops at the first match.
     *
     * @param commit the commit to walk back from
     * @param messageFragment the literal text to look for in each commit message; never blank
     * @return {@code true} when at least one reachable commit carries the fragment
     */
    public boolean historyContains(ObjectId commit, String messageFragment) {
        if (messageFragment.isBlank()) {
            throw new IllegalArgumentException("messageFragment must not be blank");
        }
        GitExec.Result result = exec.run(
                List.of("rev-list", "--max-count=1", "--fixed-strings", "--grep=" + messageFragment, commit.hex()));
        // "a commit id was printed": rev-list writes its diagnostics to stderr, so an
        // unresolvable commit leaves stdout empty exactly as a clean no-match does.
        return !result.stdoutText().isBlank();
    }

    /**
     * The full commit message — subject and body — of one commit, as the trailers a reader parses
     * out of it need (FR13 of harden-task-branch-contract). Empty when the commit does not resolve,
     * so an unreadable tip is a missing message rather than a thrown failure.
     *
     * @param commit the commit whose message is read
     * @return the raw message, or empty when the commit does not resolve
     */
    public Optional<String> commitMessage(ObjectId commit) {
        GitExec.Result result = exec.run(List.of("log", "-1", "--format=%B", commit.hex()));
        return result.exitCode() == 0 ? Optional.of(result.stdoutText()) : Optional.empty();
    }

    /**
     * Builds a commit from {@code request} and advances its ref. Throws {@link StaleTipException} if
     * the ref's compare-and-swap precondition does not hold (the branch moved concurrently).
     */
    public ObjectId commit(CommitRequest request) {
        return commitBuilder.build(request, resolveRef(request.ref()).orElse(null));
    }
}
