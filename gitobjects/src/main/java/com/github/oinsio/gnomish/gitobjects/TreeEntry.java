package com.github.oinsio.gnomish.gitobjects;

/**
 * One entry of a git tree as {@link GitObjects#listTree} reports it: the entry's own name inside the
 * listed tree — never a path — and the kind of thing that name holds.
 *
 * <p>The kinds are kept apart on purpose. A reader that binds pipeline law out of bare objects has
 * no {@code realpath} to resolve a link against, so it must be able to see that an entry is a
 * symlink and refuse it, rather than reading it as a file whose bytes are a path (design D12).
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 *
 * @param name the entry's name within the listed tree
 * @param kind what the entry holds
 */
public record TreeEntry(String name, Kind kind) {

    /** What a tree entry holds, as the git file mode of the entry says. */
    public enum Kind {
        /** A regular file — an executable one included; a blob either way. */
        FILE,
        /** A subdirectory: another tree. */
        DIRECTORY,
        /** A symbolic link, whose blob content is the link target. */
        SYMLINK,
        /**
         * Anything else git may record in a tree — today a gitlink (a submodule's pinned commit).
         * Deliberately one bucket: a reader that only understands the three kinds above must treat
         * every other mode as unreadable, and a new mode arriving in git lands here rather than
         * being mistaken for a file.
         */
        OTHER;

        /** The kind the six-digit git file {@code mode} names; unknown modes are {@link #OTHER}. */
        static Kind ofMode(String mode) {
            return switch (mode) {
                case "100644", "100755" -> FILE;
                case "040000" -> DIRECTORY;
                case "120000" -> SYMLINK;
                default -> OTHER;
            };
        }
    }
}
