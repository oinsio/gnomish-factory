package com.github.oinsio.gnomish.executorcontract;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a result file is read from: the one byte-reading seam {@link ResultFileReader} reads
 * through (FR6 of define-executor-contract). The path behind it is the factory's choice, made
 * before the executor ran; a source never takes a path from the content it returns.
 *
 * <p>An execution environment adapts its own capped channel read to this seam — {@code limit ->
 * environment.readFile(path, limit)} — so a read inside a box stays a box read; a file the factory
 * created on the host is read through {@link #hostFile(Path)}. This module reaches neither the
 * environment nor any factory layer: the seam is the whole of what the reader needs from them.
 *
 * <p>Implements FR6, NFR-S2 of define-executor-contract.
 */
public interface ResultSource {

    /**
     * Reads at most {@code limit} bytes from the start of the file.
     *
     * @param limit the most bytes to return; always positive
     * @return the bytes read, at most {@code limit} of them, or empty when no file exists
     */
    Optional<byte[]> read(long limit);

    /**
     * A file on the factory's own filesystem, at a path the factory created for the run. A symbolic
     * link or any other non-regular file at the path is refused, never followed.
     *
     * @param path the factory-chosen path; never null
     * @return the source; never null
     */
    static ResultSource hostFile(Path path) {
        return new HostResultFile(path);
    }
}
