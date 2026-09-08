package com.github.oinsio.gnomish.adapter.law;

import java.io.IOException;
import java.util.List;

/**
 * Where the pipeline law is read from (design D12 of add-base-ref-resolution): exactly three
 * operations — read a file, test whether a path is a regular file, list a directory — all
 * relative to one law root, and nothing else. Everything that binds law ({@code GnomishFiles},
 * {@code ReferencedFiles}, {@code PipelineLoader}, {@link PipelineLawReader}) reads through this
 * seam, so "which tree is the law in" is answered once, at assembly, instead of once per reader.
 *
 * <p>Two realizations, and deliberately no more: {@link WorkingTreeLawSource} over a directory of
 * the factory clone — the in-place mode and manual {@code run} without {@code --base}, where an
 * uncommitted edit is meant to be law — and {@link GitObjectsLawSource} bound to one commit, the
 * <em>law commit</em>, for every path that resolved a ref. They are two strategies behind one
 * interface, not a hand-synchronized pair: one contract spec runs both over identical trees.
 *
 * <p><b>Faults versus data.</b> A file that cannot be read is <em>data</em> — an
 * {@link Unreadable} carrying the reason, because freezing a whole law must not fail on one
 * missing stage file (the failure surfaces at that stage's point of use). A directory listing
 * behaves the other way: a path that names no directory is an empty listing, and a genuine I/O
 * fault propagates as an {@link IOException}, which is the loader's existing contract.
 *
 * <p><b>Traversal.</b> Every operation resolves its argument against the root and refuses
 * anything that escapes it, before the outside path is read, existence-checked, or listed. One
 * shared segment walk ({@code LawPathWalk}) does that for both realizations over their own tree
 * entries, so a symlink at any segment is refused with one verdict in every medium.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 */
public interface LawSource {

    /** The outcome of reading one law file: its text, or the reason it could not be read. */
    sealed interface Read permits Text, Unreadable {}

    /**
     * The law file's content.
     *
     * @param text the file's text, decoded as UTF-8
     */
    record Text(String text) implements Read {}

    /**
     * The law file could not be read — absent, refused by the traversal guard, over the read cap,
     * or an I/O fault.
     *
     * @param reason a human-readable reason, carried to the point of use
     */
    record Unreadable(String reason) implements Read {}

    /** What a path is, as far as reading law from it goes. */
    enum FileStatus {
        /** A regular file the law may be read from. */
        REGULAR_FILE,
        /** Nothing readable at that path: absent, or a directory rather than a file. */
        ABSENT,
        /**
         * The traversal guard refused the path: it escapes the law root, or a segment of it — the
         * leaf or a directory on the way — is a symlink entry, in either medium.
         */
        REFUSED
    }

    /**
     * Reads the law file at {@code ref}, relative to the law root.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     *
     * @param ref a root-relative path, exactly as a manifest writes it
     * @return the file's text, or the reason it is unreadable; never null
     */
    Read read(String ref);

    /**
     * Classifies {@code ref}, relative to the law root, without reading it.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     *
     * @param ref a root-relative path, exactly as a manifest writes it
     * @return whether the path is a readable regular file, absent, or refused; never null
     */
    FileStatus fileStatus(String ref);

    /**
     * Lists the entries of the directory at {@code ref}, relative to the law root; the empty
     * string lists the root itself. Order is the medium's own and unspecified — a caller that
     * needs determinism sorts.
     *
     * <p>Implements FR11 of add-base-ref-resolution.
     *
     * @param ref a root-relative directory path; the empty string for the root
     * @return the directory's entries, or an empty list when {@code ref} names no directory —
     *     absent, a regular file, or refused by the traversal guard
     * @throws IOException when the directory exists but cannot be enumerated
     */
    List<LawEntry> list(String ref) throws IOException;
}
