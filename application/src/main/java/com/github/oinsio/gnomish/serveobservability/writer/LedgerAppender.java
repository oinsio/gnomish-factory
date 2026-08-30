package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.atomicfile.NonAtomicWrite;
import com.github.oinsio.gnomish.serveobservability.LedgerLine;
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The shared, synchronized append point behind every ledger line (design
 * D8): serialize a {@link LedgerLine} to its compact JSON form via {@link
 * LedgerJsonMapper}, then append it plus a trailing newline to the target
 * file and flush — under one {@code synchronized} block so concurrent slot
 * completions (design D8, NFR-R3) never interleave two lines' bytes.
 *
 * <p>Write-only: the target file is opened, appended to, and closed on every
 * call — never read back, never fsync'd (design D5, NFR-R2). Crash
 * consistency is deliberately minimal; a torn last line after a crash is
 * legal and readers MUST tolerate it. This class owns no background thread
 * and no buffering across calls, so it has no in-process state to lose.
 *
 * <p>The target is a plain mutable field guarded by the same {@code
 * synchronized} discipline as the write itself, so a future daily-rotation
 * layer (design D7, task 4.2) can redirect subsequent appends to a new file
 * without reconstructing this object or racing an in-flight append.
 *
 * <p>Implements NFR-R2, NFR-R3 of add-serve-observability.
 */
@NonAtomicWrite("append-only: the ledger grows by adding a line, and a replace-by-rename would drop"
        + " every line already there. A torn last line after a crash is legal here by design (NFR-R2) and"
        + " readers tolerate it.")
public final class LedgerAppender {

    private final LedgerJsonMapper jsonMapper;
    private Path target;

    /**
     * @param target the file appended to; created (with parent directories) on
     *     first append if it does not yet exist; never null
     * @param jsonMapper serializes each {@link LedgerLine} to its JSONL contract;
     *     never null
     */
    public LedgerAppender(Path target, LedgerJsonMapper jsonMapper) {
        this.target = target;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Serializes {@code line} and appends it as one JSON line, flushed
     * immediately, no fsync (design D5). Safe to call from any number of
     * threads concurrently (design D8, NFR-R3): the whole serialize-append-
     * flush sequence is serialized so no two calls' bytes can interleave.
     *
     * @param line the ledger line to append; never null
     * @throws IOException if the parent directory cannot be created or the
     *     append write fails
     */
    public synchronized void append(LedgerLine line) throws IOException {
        String json = jsonMapper.serialize(line);
        Path directory = target.toAbsolutePath().normalize().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Files.writeString(
                target,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Redirects subsequent {@link #append} calls to {@code newTarget} without
     * touching the previous file (design D7: rotation is a name switch, the
     * live file is never renamed). Guarded by the same lock as {@link #append}
     * so a rotation never lands mid-append.
     *
     * @param newTarget the file subsequent appends write to; never null
     */
    public synchronized void retarget(Path newTarget) {
        this.target = newTarget;
    }
}
