package com.github.oinsio.gnomish.adapter.law;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One realization's view of the tree under its law root, reduced to the single question the
 * shared {@link LawPathWalk} asks at every segment: what does the entry at this root-relative path
 * hold? A working tree answers from the filesystem without following links; a git tree answers
 * from the parent tree's entry modes. Neither classifies a whole reference — that is the walk's
 * job, written once — so the two media cannot disagree on where a symlink stops the law.
 *
 * <p>Implements FR11 of add-base-ref-resolution (design D12).
 */
interface LawTree {

    /** What one tree entry holds, as far as walking a law path goes. */
    enum Node {
        /** A regular file: a candidate law file. */
        FILE,
        /** A directory: the walk may continue beneath it. */
        DIRECTORY,
        /** A symbolic link: the walk refuses here, whatever the link's target. */
        SYMLINK,
        /** Anything else — a device node, a socket, a submodule's pinned commit — never law. */
        OTHER
    }

    /**
     * Classifies the entry at {@code relative}, a normalized path below the law root with at least
     * one segment, without following any link.
     *
     * @param relative the root-relative path of the entry; never the root itself
     * @return the entry's node kind, or {@code null} when the tree holds no entry there
     */
    @Nullable
    Node node(Path relative);
}
