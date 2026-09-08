package com.github.oinsio.gnomish.adapter.law;

import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import java.nio.file.Path;

/**
 * The one segment walk that classifies a law reference for both {@link LawSource} realizations
 * (design D12 of add-base-ref-resolution): resolve the reference lexically against the law root,
 * then step down the resolved path one segment at a time, asking the realization's {@link LawTree}
 * what each entry holds. Written once, so the verdict "where does a symlink stop the law" is the
 * same in a working tree and in git objects by construction, not by two hand-kept copies.
 *
 * <p><b>Fail-closed at any segment.</b> A symlink entry at <em>any</em> segment — a leaf or a
 * directory on the way — is {@link Symlinked}, whatever its target: a configuration tree has no
 * legitimate link, so the target is never parsed (Kustomize's rule). A symlinked directory is
 * therefore refused rather than reported absent, which plain {@code ls-tree} cannot tell apart
 * from a deleted directory — which is why the walk, not git, classifies. Nothing beneath a
 * non-directory is law either: an entry under a regular file or a gitlink is {@link Absent}.
 *
 * <p>The lexical half of {@link PathSafety} is the whole traversal guard: with every segment
 * classified without following links, a {@code realpath} comparison could never differ from the
 * lexical one, so no realization keeps a second guard the specs could not kill.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 */
final class LawPathWalk {

    private LawPathWalk() {}

    /** Where a law reference lands, once walked. */
    sealed interface Verdict permits Escapes, Symlinked, Absent, Root, File, Directory, Other {}

    /**
     * The reference climbs out of the law root lexically — {@code ../} or an absolute path.
     *
     * @param ref the offending reference as written
     */
    record Escapes(String ref) implements Verdict {}

    /**
     * A segment of the reference is a symlink entry; nothing at or beneath it is law.
     *
     * @param segment the root-relative path of the refusing entry
     */
    record Symlinked(Path segment) implements Verdict {}

    /**
     * The tree holds nothing at the reference.
     *
     * @param relative the root-relative path that was looked for
     */
    record Absent(Path relative) implements Verdict {}

    /** The reference names the law root itself. */
    record Root() implements Verdict {}

    /**
     * The reference names a regular file.
     *
     * @param relative the file's root-relative path
     */
    record File(Path relative) implements Verdict {}

    /**
     * The reference names a directory.
     *
     * @param relative the directory's root-relative path
     */
    record Directory(Path relative) implements Verdict {}

    /**
     * The reference names an entry that is neither file, directory nor link.
     *
     * @param relative the entry's root-relative path
     */
    record Other(Path relative) implements Verdict {}

    /**
     * Walks {@code ref} against {@code root} over {@code tree}.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     *
     * @param root the law root the reference resolves against, in the realization's own path space
     * @param ref the reference exactly as a manifest writes it; the empty string names the root
     * @param tree the realization's entries
     * @return where the reference lands; never null
     */
    static Verdict walk(Path root, String ref, LawTree tree) {
        if (!(PathSafety.resolveWithinRootLexically(root, ref) instanceof PathSafety.Within(Path within))) {
            return new Escapes(ref);
        }
        Path relative = root.normalize().relativize(within);
        if (relative.toString().isEmpty()) {
            return new Root();
        }
        Path walked = Path.of("");
        LawTree.Node node = LawTree.Node.DIRECTORY; // the root itself: a directory by definition
        for (Path segment : relative) {
            walked = walked.resolve(segment);
            node = tree.node(walked);
            if (node == null) {
                return new Absent(relative);
            }
            if (node == LawTree.Node.SYMLINK) {
                return new Symlinked(walked);
            }
        }
        return switch (node) {
            case FILE -> new File(relative);
            case DIRECTORY -> new Directory(relative);
            case OTHER -> new Other(relative);
            case SYMLINK -> throw new IllegalStateException("a symlink segment is refused inside the walk");
        };
    }

    /**
     * The {@link LawSource.FileStatus} a {@link Verdict} carries — identical for both realizations,
     * since a verdict already resolved the medium-specific traversal.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     */
    static LawSource.FileStatus fileStatus(Verdict verdict) {
        return switch (verdict) {
            case Escapes _, Symlinked _ -> LawSource.FileStatus.REFUSED;
            case File _ -> LawSource.FileStatus.REGULAR_FILE;
            case Absent _, Directory _, Other _, Root _ -> LawSource.FileStatus.ABSENT;
        };
    }
}
