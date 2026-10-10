package com.github.oinsio.gnomish.app.serve;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * How old a task environment is, read from the files under it — the activity measure the {@link
 * WorktreeJanitor}'s age policy compares against its threshold (design D10 of add-factory-serve).
 *
 * <p>Implements FR14 of add-factory-serve.
 */
final class WorktreeActivity {

    private WorktreeActivity() {}

    /**
     * The instant of the most recently modified regular file anywhere under {@code dir}, or
     * {@code dir}'s own last-modified time if it contains none — task activity (writes under
     * {@code .gnomish-task/}, gnome edits) touches nested files, not necessarily the top-level
     * worktree directory entry itself, so the whole tree is walked rather than reading one
     * directory timestamp.
     */
    static Instant lastActivity(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile)
                    .map(WorktreeActivity::modifiedInstant)
                    .max(Instant::compareTo)
                    .orElseGet(() -> modifiedInstant(dir));
        } catch (IOException e) {
            throw new UncheckedIOException("worktree janitor: failed to read " + dir, e);
        }
    }

    private static Instant modifiedInstant(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("worktree janitor: failed to stat " + path, e);
        }
    }
}
