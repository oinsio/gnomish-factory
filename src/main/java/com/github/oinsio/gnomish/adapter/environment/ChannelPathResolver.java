package com.github.oinsio.gnomish.adapter.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Resolves a factory-chosen file-channel path against the host environment's two
 * owned roots — the working copy and the per-environment scratch area — and
 * refuses anything escaping them, symlinks included (design D1, D16). One method
 * serves both reads and writes: it anchors on the deepest existing component's
 * real (symlink-resolved) path, so an existing symlink that points outside a
 * root and a not-yet-created file under a symlinked parent are both caught.
 *
 * <p>Implements NFR-S3, FR17 of add-sandbox-core.
 */
record ChannelPathResolver(Path workingCopyReal, Path scratchReal) {

    static ChannelPathResolver of(Path workingCopy, Path scratch) {
        return new ChannelPathResolver(realPath(workingCopy), realPath(scratch));
    }

    /**
     * Resolves {@code path} — absolute as given, or relative to the working copy
     * — normalizes it, and refuses it unless the deepest existing component's
     * real path lies under the working copy or the scratch root. {@code .git/**}
     * under the working copy is refused outright, symlinks resolved first, so no
     * channel write can plant a hook or rewrite repository internals even from
     * inside the root (FR17 — the confinement any model-output application
     * inherits by running through this channel).
     *
     * @param path the factory-chosen path; never null
     * @return the resolved, normalized path to read or write; never null
     * @throws PathEscapeException if the path escapes both roots, enters {@code
     *     .git/**}, or is malformed
     */
    Path resolve(String path) {
        Path normalized = normalize(path);
        Path anchor = deepestExistingReal(normalized, path);
        if (!isUnder(anchor, workingCopyReal) && !isUnder(anchor, scratchReal)) {
            throw new PathEscapeException(path);
        }
        Path gitDir = workingCopyReal.resolve(".git");
        if (isUnder(normalized, gitDir) || isUnder(anchor, gitDir)) {
            throw new PathEscapeException(path);
        }
        return normalized;
    }

    private Path normalize(String path) {
        try {
            Path raw = Path.of(path);
            Path abs = raw.isAbsolute() ? raw : workingCopyReal.resolve(raw);
            return abs.normalize();
        } catch (InvalidPathException e) {
            throw new PathEscapeException(path);
        }
    }

    private static Path deepestExistingReal(Path normalized, String original) {
        Path cur = normalized;
        while (cur != null && !Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
            cur = cur.getParent();
        }
        if (cur == null) {
            throw new PathEscapeException(original);
        }
        return realPath(cur);
    }

    private static boolean isUnder(Path candidate, Path root) {
        return candidate.equals(root) || candidate.startsWith(root);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new PathEscapeException(path.toString());
        }
    }
}
