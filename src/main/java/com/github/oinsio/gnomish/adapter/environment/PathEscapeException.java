package com.github.oinsio.gnomish.adapter.environment;

/**
 * Thrown when a factory-chosen file-channel path resolves outside the
 * environment's two owned roots — the working copy or the per-environment
 * scratch area — including via a planted symlink (design D1, D16). The read or
 * write is refused and reported as a violation; no file outside the roots is
 * opened (NFR-S3, FR17).
 *
 * <p>Implements NFR-S3, FR17 of add-sandbox-core.
 */
public final class PathEscapeException extends RuntimeException {

    /**
     * @param path the offending factory-chosen path, for the violation report;
     *     never null
     */
    public PathEscapeException(String path) {
        super("file-channel path escapes the environment roots: " + path);
    }
}
