package com.github.oinsio.gnomish.gitobjects;

/**
 * Base unchecked failure of the {@link GitObjects} library — most often an unexpected non-zero exit
 * from a git plumbing command. Subtypes mark the outcomes callers branch on (a stale ref, a missing
 * object, an oversized blob, an invalid path).
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public class GitObjectsException extends RuntimeException {

    public GitObjectsException(String message) {
        super(message);
    }

    public GitObjectsException(String message, Throwable cause) {
        super(message, cause);
    }
}
