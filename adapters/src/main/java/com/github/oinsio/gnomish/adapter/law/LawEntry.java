package com.github.oinsio.gnomish.adapter.law;

/**
 * One entry of a directory as {@link LawSource#list} reports it: the entry's own name within the
 * listed directory — never a path — and the kind of thing that name holds.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 *
 * @param name the entry's name within the listed directory
 * @param kind what the entry holds
 */
public record LawEntry(String name, Kind kind) {

    /** What a directory entry holds, as far as law binding is concerned. */
    public enum Kind {
        /** A regular file — a candidate law file. */
        FILE,
        /** A subdirectory, which may hold law files of its own. */
        DIRECTORY,
        /**
         * Anything else: a symlink, a dangling link, a submodule's pinned commit, a device node.
         * Deliberately one bucket — a law reader understands files and directories, and every
         * other entry is something it must not follow.
         */
        OTHER
    }
}
