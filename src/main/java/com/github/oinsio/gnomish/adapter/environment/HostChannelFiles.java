package com.github.oinsio.gnomish.adapter.environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Stateless filesystem mechanics for {@link HostTaskExecutionEnvironment}:
 * channel file read/write through a {@link ChannelPathResolver} boundary, and
 * scratch-area teardown. Extracted from {@code HostTaskExecutionEnvironment}
 * for file size; the behavior is unchanged, including which logger emits each
 * message (callers pass the original class's {@link Logger} through).
 */
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

    static void deleteRecursively(Path root, Logger log) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> deleteQuietly(p, log));
        } catch (IOException e) {
            log.warn("could not fully remove scratch area {}: {}", root, e.toString());
        }
    }

    private static void deleteQuietly(Path path, Logger log) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("could not remove scratch entry {}: {}", path, e.toString());
        }
    }
}
