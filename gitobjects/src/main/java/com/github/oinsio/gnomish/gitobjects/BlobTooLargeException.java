package com.github.oinsio.gnomish.gitobjects;

/**
 * Thrown by {@link GitObjects#readBlob} when a blob exceeds the caller's size cap. The library
 * refuses to truncate a factory-authored file silently (that would corrupt JSON state); the caller
 * either raised the wrong cap or the object is not what it expected (NFR-S3, bounded reads).
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public final class BlobTooLargeException extends GitObjectsException {

    public BlobTooLargeException(String message) {
        super(message);
    }
}
