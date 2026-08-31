package com.github.oinsio.gnomish.atomicfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The factory's one atomic file-write primitive (design D4 of add-serve-observability, generalized
 * by design D10 of harden-task-branch-contract): write the full content to a temp file in the
 * <em>same directory</em> as the target, then {@link Files#move move} it onto the target with
 * {@link StandardCopyOption#ATOMIC_MOVE}. Same-directory placement matters — an atomic rename is
 * only atomic within one filesystem/mount, so a temp file elsewhere could silently fall back to a
 * non-atomic copy. A reader opening the target path therefore only ever sees the previous complete
 * content or the new complete content, never a partially written file.
 *
 * <p>Every host-side writer of a factory-owned file goes through here: the {@code .gnomish-task/}
 * writers on the task worktree and the observability snapshot and dashboard writers. The
 * container-side persisters consume it not at all — they reach durability at commit granularity,
 * per the per-medium table of {@code docs/adr/0003-crash-consistency.md}.
 *
 * <p>The temp file is best-effort cleaned up on failure so a crash mid-write does not leave litter
 * behind; the target file itself is untouched until the rename succeeds.
 *
 * <p>Implements FR1 of add-serve-observability; FR5 of harden-task-branch-contract.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {}

    /**
     * Atomically replaces {@code target} with {@code content}, creating the parent
     * directory if it does not yet exist.
     *
     * @param target the file to overwrite; its parent directory hosts the temp file
     * @param content the full new content; never null
     * @throws IOException if the parent directory cannot be created, the temp file
     *     cannot be written, or the atomic move fails
     */
    public static void write(Path target, String content) throws IOException {
        Path directory = target.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            throw new IOException("target has no parent directory: " + target);
        }
        Files.createDirectories(directory);
        Path tempFile = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
