package com.github.oinsio.gnomish.gitobjects;

/**
 * Thrown when a tree-edit or read path is not a clean repository-relative path (absolute, contains
 * {@code .}/{@code ..} or an empty segment, or touches {@code .git}). See {@link TreePaths}.
 *
 * <p>Implements FR25, FR17 of add-sandbox-core.
 */
public final class InvalidTreePathException extends GitObjectsException {

    public InvalidTreePathException(String path, String reason) {
        super("invalid tree path '" + path + "': " + reason);
    }
}
