package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.atomicfile.NonAtomicWrite;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Stateless filesystem mechanics for {@link HostTaskExecutionEnvironment}:
 * channel file read/write through a {@link ChannelPathResolver} boundary, and
 * scratch-area teardown. Extracted from {@code HostTaskExecutionEnvironment}
 * for file size; the behavior is unchanged, including which logger emits each
 * message (callers pass the original class's {@link Logger} through).
 */
@NonAtomicWrite("channel scratch handed to a task subprocess, not factory-owned state: nothing under"
        + " .gnomish-task/ passes through here and no other instance classifies a task from it.")
final class HostChannelFiles {

    private HostChannelFiles() {}

    static void putFile(ChannelPathResolver channels, String path, byte[] content) {
        Path resolved = channels.resolve(path);
        try {
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(resolved, content);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write channel file " + path, e);
        }
    }

    static Optional<byte[]> readFile(ChannelPathResolver channels, String path, long sizeCap, Logger log) {
        if (sizeCap <= 0) {
            throw new IllegalArgumentException("readFile sizeCap must be positive, got " + sizeCap);
        }
        Path resolved = channels.resolve(path);
        if (!Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(resolved)) {
            byte[] capped = in.readNBytes((int) Math.min(sizeCap, Integer.MAX_VALUE));
            if (in.read() != -1) {
                log.warn("channel file {} exceeded read cap {} bytes; truncated", path, sizeCap);
            }
            return Optional.of(capped);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read channel file " + path, e);
        }
    }

    /**
     * Removes the scratch area, deepest entry first, and reports what it could not remove as
     * <b>one</b> line (D4 of harden-logging-observability): a scratch tree the factory cannot
     * delete usually cannot delete any of it — a busy mount, a permission change — so a line per
     * entry turns one fault into thousands. The count is what the operator needs; the first
     * failing entry and its reason are what makes the count diagnosable. The aggregate is per
     * call, which is why it is a local counter and not the cross-call {@code RepeatSuppressor}.
     */
    static void deleteRecursively(Path root, Logger log) {
        var failures = new DeleteFailures();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> deleteQuietly(p, failures));
        } catch (IOException e) {
            log.warn("could not fully remove scratch area {}", root, e);
            return;
        }
        failures.report(root, log);
    }

    private static void deleteQuietly(Path path, DeleteFailures failures) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            failures.record(path, e);
        }
    }

    /** The entries one teardown could not remove, counted so the whole walk costs one line. */
    private static final class DeleteFailures {

        private int count;
        private @Nullable Path firstPath;
        private @Nullable IOException firstFailure;

        void record(Path path, IOException failure) {
            if (firstFailure == null) {
                firstPath = path;
                firstFailure = failure;
            }
            count++;
        }

        void report(Path root, Logger log) {
            if (firstFailure == null) {
                return;
            }
            log.warn(
                    "could not remove {} entries under scratch area {}; the first was {}",
                    count,
                    root,
                    firstPath,
                    firstFailure);
        }
    }
}
