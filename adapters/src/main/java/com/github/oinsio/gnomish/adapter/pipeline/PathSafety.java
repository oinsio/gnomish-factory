package com.github.oinsio.gnomish.adapter.pipeline;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * The loader's path-traversal guard (task 6.4, NFR-S2): resolves a referenced-file
 * path from a manifest against the {@code .gnomish/} root and rejects anything that
 * escapes the root, <em>before</em> the outside file is ever existence-checked or read.
 *
 * <p><b>Composition with the existence check (task 6.3).</b> This guard is the seam
 * {@link ReferencedFiles} delegates to: for each reference the loader path is
 * resolve-within-root (6.4) → and only if {@link Within}, existence (6.3). Traversal
 * is decided first — an escaping reference is reported as a traversal problem and its
 * existence is never checked, so a single reference never yields both a traversal and
 * a "does not exist" error.
 *
 * <p><b>Two escape mechanisms, two techniques.</b>
 *
 * <ul>
 *   <li><b>Lexical escape</b> — {@code ../} traversal that climbs above the root, or an
 *       absolute path pointing elsewhere. Caught by normalizing the resolved path
 *       ({@link Path#normalize()}) and asserting it still starts with the normalized
 *       root. This is purely lexical: it needs no file to exist, so a traversing
 *       reference to a non-existent path is still rejected.</li>
 *   <li><b>Symlink escape</b> — a link that lexically sits inside the root but whose
 *       real target is outside it. {@code normalize()} cannot see through a symlink, so
 *       when the resolved path exists it is canonicalized with {@link Path#toRealPath(java.nio.file.LinkOption...)}
 *       and the real path must start with the root's real path. This check only applies
 *       when the path exists; a not-yet-existing reference cannot be a live symlink and
 *       is left to lexical judgement (its existence is 6.3's concern).</li>
 * </ul>
 *
 * <p><b>Read-only (NFR-R1) and no execution (NFR-S1).</b> Resolution and
 * canonicalization only inspect path structure and the filesystem's link metadata; the
 * referenced file's contents are never opened.
 *
 * <p>Implements NFR-S2 of load-pipeline-config.
 */
public final class PathSafety {

    private PathSafety() {}

    /**
     * The outcome of resolving a reference against the root: either a {@link Within}
     * carrying the safe resolved path, or an {@link Escapes} naming the offending
     * reference. Sealed so the caller switches exhaustively.
     */
    public sealed interface Resolution permits Within, Escapes {}

    /**
     * The reference resolves safely under the root. The carried {@code path} is the
     * normalized resolved path, which {@link ReferencedFiles} then existence-checks.
     *
     * @param path the normalized path under the root
     */
    public record Within(Path path) implements Resolution {}

    /**
     * The reference escapes the root (lexical {@code ../}/absolute climb, or a symlink
     * whose real target is outside). The outside file is never existence-checked or read.
     *
     * @param ref the offending reference exactly as written in the manifest
     */
    public record Escapes(String ref) implements Resolution {}

    /**
     * Resolves {@code ref} against {@code root} and classifies it as {@link Within} or
     * {@link Escapes}.
     *
     * <p>Implements NFR-S2 of load-pipeline-config.
     *
     * @param root the {@code .gnomish/} directory root
     * @param ref a non-blank referenced-file path from a manifest
     * @return {@link Within} with the resolved path when it stays under the root, else
     *     {@link Escapes} naming {@code ref}
     */
    public static Resolution resolveWithinRoot(Path root, String ref) {
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(ref).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            return new Escapes(ref);
        }
        if (escapesViaSymlink(normalizedRoot, resolved)) {
            return new Escapes(ref);
        }
        return new Within(resolved);
    }

    /**
     * True when the resolved path exists and, once symlinks are followed, its real path
     * leaves the root's real path. A path that does not exist cannot be a live symlink,
     * so it is not a symlink escape (its existence is task 6.3's concern). An I/O fault
     * while canonicalizing is treated as an escape — the guard refuses to vouch for a
     * path it cannot fully resolve.
     */
    private static boolean escapesViaSymlink(Path normalizedRoot, Path resolved) {
        try {
            Path realRoot = normalizedRoot.toRealPath();
            Path realResolved = resolved.toRealPath();
            return !realResolved.startsWith(realRoot);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
