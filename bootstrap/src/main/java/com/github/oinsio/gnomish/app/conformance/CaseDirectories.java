package com.github.oinsio.gnomish.app.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * The working copy of one conformance case (FR12 of define-executor-contract; design D19): a fresh
 * directory under the kit's scratch root, named after the case, so a request's workspace path ends in
 * {@code /<case>} — the name the misbehaving fake and the in-process double break a case by. One
 * spelling for every subject, the program and the built-in alike.
 *
 * <p>Implements FR12 of define-executor-contract.
 */
@NullMarked
final class CaseDirectories {

    private CaseDirectories() {}

    /**
     * Creates the working copy of {@code kase} under {@code scratchRoot}.
     *
     * @param scratchRoot the existing directory every case's working copy is made in; never null
     * @param kase the case; never null
     * @return the new, empty directory; never null
     * @throws UncheckedIOException if the directory could not be created, or already exists
     */
    static Path create(Path scratchRoot, KitCase kase) {
        try {
            return Files.createDirectory(scratchRoot.resolve(kase.word()));
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the working copy of case " + kase.word(), e);
        }
    }
}
