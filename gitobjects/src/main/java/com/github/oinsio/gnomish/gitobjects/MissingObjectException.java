package com.github.oinsio.gnomish.gitobjects;

/**
 * Thrown by {@link GitObjects#readBlob} when the requested path does not exist in the given commit's
 * tree. Distinct from a truncation or a generic git error so a caller reading a state file it
 * expects to be present can tell "absent" from "too large" or "git broke".
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public final class MissingObjectException extends GitObjectsException {

    public MissingObjectException(String message) {
        super(message);
    }
}
