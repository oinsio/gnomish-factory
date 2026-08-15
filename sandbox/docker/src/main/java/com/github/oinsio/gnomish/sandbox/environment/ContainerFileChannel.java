package com.github.oinsio.gnomish.sandbox.environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container adapter's factory↔environment file channel (design D1, D16): a
 * factory-authored file written and read <em>through {@code docker exec}</em>,
 * streaming bytes over the exec pipe — never {@code docker cp}, whose host-side
 * tar extraction carries a CVE class the design forbids (D16). One instance per
 * materialized environment, sharing the task container the adapter already
 * created.
 *
 * <p>Paths are factory-chosen and validated against the two environment-owned
 * roots — the working copy and the per-environment scratch area — refusing
 * anything that normalizes outside both (a {@code ..} escape). Unlike the host
 * adapter, there is no host-side symlink resolution: the bytes never touch a
 * factory filesystem, so an in-box symlink can at worst redirect a read within
 * the box the gnome already controls, and reads run as the in-box task user, not
 * root (D16).
 *
 * <p>Implements FR1, NFR-S3 of add-sandbox-core.
 */
final class ContainerFileChannel {

    private static final Logger log = LoggerFactory.getLogger(ContainerFileChannel.class);

    // Writes to the factory-chosen path via a positional arg ($1), never string-interpolated into
    // the script — so a path can carry no shell metacharacter that alters the command.
    private static final String WRITE_SCRIPT = "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"";

    // Absent file exits 42 (distinct from any cat error) so readFile tells "no such file" (empty)
    // apart from a present file; head -c bounds output at the source, avoiding a host-side
    // read-then-kill truncation race on the exec pipe.
    private static final String READ_SCRIPT = "if [ -f \"$1\" ]; then head -c \"$2\" \"$1\"; else exit 42; fi";
    private static final int ABSENT_EXIT = 42;

    private final DockerCli docker;
    private final String key;
    private final Path workingCopy;
    private final Path scratch;

    ContainerFileChannel(DockerCli docker, String key, String workingCopy, String scratch) {
        this.docker = docker;
        this.key = key;
        this.workingCopy = Path.of(workingCopy);
        this.scratch = Path.of(scratch);
    }

    void putFile(String path, byte[] content) {
        String resolved = validate(path);
        List<String> argv = List.of("sh", "-c", WRITE_SCRIPT, "gnomish", resolved);
        Process process = docker.start(DockerCommands.exec(key, workingCopy.toString(), Map.of(), true, argv), false);
        Thread pump = Thread.ofVirtual().start(() -> pump(process, content));
        drain(process.getInputStream());
        int code = waitFor(process);
        join(pump);
        if (code != 0) {
            throw new UncheckedIOException(new IOException("in-box write to " + path + " failed with exit " + code));
        }
    }

    Optional<byte[]> readFile(String path, long sizeCap) {
        if (sizeCap <= 0) {
            throw new IllegalArgumentException("readFile sizeCap must be positive, got " + sizeCap);
        }
        String resolved = validate(path);
        long bound = sizeCap + 1;
        List<String> argv = List.of("sh", "-c", READ_SCRIPT, "gnomish", resolved, Long.toString(bound));
        Process process = docker.start(DockerCommands.exec(key, workingCopy.toString(), Map.of(), false, argv), false);
        byte[] bytes = drain(process.getInputStream());
        int code = waitFor(process);
        if (code == ABSENT_EXIT) {
            return Optional.empty();
        }
        if (code != 0) {
            throw new UncheckedIOException(new IOException("in-box read of " + path + " failed with exit " + code));
        }
        if (bytes.length > sizeCap) {
            log.warn("channel file {} exceeded read cap {} bytes; truncated", path, sizeCap);
            byte[] capped = new byte[(int) sizeCap];
            System.arraycopy(bytes, 0, capped, 0, capped.length);
            return Optional.of(capped);
        }
        return Optional.of(bytes);
    }

    /**
     * Normalizes {@code path} and refuses it unless it resolves under the working
     * copy or scratch root — the container-side twin of the host adapter's
     * {@link ChannelPathResolver}, without symlink resolution (D16). Relative
     * paths anchor on the working copy, matching the host contract.
     */
    // FR17: .git/** under the working copy is refused alongside root escapes, so no channel
    // write can plant a hook or rewrite repository internals in the in-box clone. Lexical only —
    // in-box symlink resolution is deliberately absent per this class's javadoc.
    private String validate(String path) {
        Path raw = Path.of(path);
        Path abs = raw.isAbsolute() ? raw : workingCopy.resolve(raw);
        Path normalized = abs.normalize();
        if (!normalized.startsWith(workingCopy) && !normalized.startsWith(scratch)) {
            throw new PathEscapeException(path);
        }
        if (normalized.startsWith(workingCopy.resolve(".git"))) {
            throw new PathEscapeException(path);
        }
        return normalized.toString();
    }

    private static void pump(Process process, byte[] bytes) {
        try (OutputStream os = process.getOutputStream()) {
            os.write(bytes);
        } catch (IOException e) {
            log.debug("in-box writer closed stdin early: {}", e.toString());
        }
    }

    private static byte[] drain(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read docker exec output", e);
        }
    }

    private static void join(Thread pump) {
        try {
            pump.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
