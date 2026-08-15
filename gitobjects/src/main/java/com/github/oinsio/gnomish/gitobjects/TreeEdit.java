package com.github.oinsio.gnomish.gitobjects;

/**
 * One change applied to a parent tree while building a commit: write a regular file, or delete a
 * path (a single file or a whole subtree). Sealed so {@link GitObjects#commit} can exhaustively
 * apply the two cases; every path is validated at construction (no absolute paths, no {@code ..},
 * nothing under {@code .git/}) via {@link TreePaths}.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public sealed interface TreeEdit permits TreeEdit.PutFile, TreeEdit.DeletePath {

    /** The repository-relative path this edit targets. */
    String path();

    /**
     * Writes {@code content} at {@code path} as a regular file (git mode {@code 100644}), creating
     * or replacing it. The array is used as-is (not defensively copied): the factory authors a fresh
     * byte array per edit, and the library never retains it beyond the commit call.
     */
    @SuppressWarnings("ArrayRecordComponent") // content is caller-owned and used once; see javadoc
    record PutFile(String path, byte[] content) implements TreeEdit {
        @SuppressWarnings({"ConstantValue", "ConstantConditions"}) // defensive: guards construction
        // paths NullAway cannot see (e.g. Groovy specs), where a null argument would otherwise reach
        // here unchecked
        public PutFile {
            TreePaths.validate(path);
            if (content == null) {
                throw new IllegalArgumentException("PutFile content must not be null: " + path);
            }
        }
    }

    /** Removes {@code path} from the tree — a single file, or every entry beneath it if a directory. */
    record DeletePath(String path) implements TreeEdit {
        public DeletePath {
            TreePaths.validate(path);
        }
    }
}
