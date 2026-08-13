package com.github.oinsio.gnomish.gitobjects;

/**
 * Validation for repository-relative paths that flow into git tree edits and blob reads. A path
 * must be relative and component-clean: no leading {@code /} (absolute), no {@code .} or {@code ..}
 * segments (escape), no empty segment (a leading, trailing, or doubled {@code /}), and no {@code
 * .git} segment (the object store and hooks live there — off-limits by construction, FR17).
 *
 * <p>Implements FR25, FR17 of add-sandbox-core.
 */
final class TreePaths {

    private TreePaths() {}

    static String validate(String path) {
        if (path.isBlank()) {
            throw new InvalidTreePathException(path, "must not be blank");
        }
        if (path.startsWith("/")) {
            throw new InvalidTreePathException(path, "must be relative, not absolute");
        }
        for (String segment : path.split("/", -1)) {
            switch (segment) {
                case "" -> throw new InvalidTreePathException(path, "must not contain an empty path segment");
                case ".", ".." -> throw new InvalidTreePathException(path, "must not contain '.' or '..'");
                case ".git" -> throw new InvalidTreePathException(path, "must not touch '.git'");
                default -> {}
            }
        }
        return path;
    }
}
