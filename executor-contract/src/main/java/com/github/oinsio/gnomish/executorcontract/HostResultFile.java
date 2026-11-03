package com.github.oinsio.gnomish.executorcontract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A result file on the factory's own filesystem (FR6, NFR-S2 of define-executor-contract). The
 * file is opened without following a symbolic link, so a link the executor planted at the path
 * cannot point the read at another file: opening it fails, and so does reading anything that is
 * not a regular file. Only a missing file is absent; every other failure is the read's failure,
 * thrown, never a result. Nothing is deleted: a read leaves the file as it found it.
 *
 * <p>Implements FR6, NFR-R1, NFR-S2 of define-executor-contract.
 */
final class HostResultFile implements ResultSource {

    private final Path path;

    HostResultFile(Path path) {
        this.path = path;
    }

    @Override
    public Optional<byte[]> read(long limit) {
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(in.readNBytes(Math.toIntExact(limit)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read result file " + path, e);
        }
    }
}
