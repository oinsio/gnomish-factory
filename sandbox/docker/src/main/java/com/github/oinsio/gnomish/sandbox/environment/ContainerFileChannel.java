package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.Supervision;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Both exec pipes are drained on virtual threads of their own, concurrently
 * with the wait ({@link ExecPipeDrain}, design D2): reading either to the end on
 * the calling thread first would let a hung in-box command hold the channel
 * forever, ahead of any supervision.
 *
 * <p>The exec's wait and kill go through the shared subprocess supervisor
 * (design D11 of bound-subprocess-commands), so an interrupted channel operation
 * ends as a named {@link Termination} — reported as an {@link
 * InterruptedIOException} — with the {@code docker exec} tree killed and reaped,
 * rather than as the exit code {@code -1} the old catch returned, which a caller
 * could not tell from an in-box script that genuinely exited {@code -1}.
 *
 * <p>Implements FR1, NFR-S3 of add-sandbox-core; FR11 of
 * bound-subprocess-commands.
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

    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor();

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
        ExecPipeDrain stdout = ExecPipeDrain.start(process.getInputStream(), "channel-write-stdout");
        ExecPipeDrain stderr = ExecPipeDrain.start(process.getErrorStream(), "channel-write-stderr");
        int code = completed(process, "in-box write to " + path);
        stdout.join();
        byte[] errBytes = stderr.join();
        join(pump);
        if (code != 0) {
            throw new UncheckedIOException(failure("in-box write to " + path, code, errBytes));
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
        ExecPipeDrain stdout = ExecPipeDrain.start(process.getInputStream(), "channel-read-stdout");
        ExecPipeDrain stderr = ExecPipeDrain.start(process.getErrorStream(), "channel-read-stderr");
        int code = completed(process, "in-box read of " + path);
        byte[] bytes = stdout.join();
        byte[] errBytes = stderr.join();
        if (code == ABSENT_EXIT) {
            return Optional.empty();
        }
        if (code != 0) {
            throw new UncheckedIOException(failure("in-box read of " + path, code, errBytes));
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

    /**
     * The nonzero-exit failure, carrying whatever the in-box command wrote to
     * stderr — without it, "failed with exit 1" left an operator to re-run the
     * exec by hand to learn which of {@code mkdir}, {@code cat} or {@code head}
     * refused, and why (NFR-O1 of bound-subprocess-commands).
     */
    private static IOException failure(String what, int code, byte[] stderr) {
        String detail = new String(stderr, StandardCharsets.UTF_8).strip();
        String suffix = detail.isEmpty() ? "" : "; stderr: " + detail;
        return new IOException(what + " failed with exit " + code + suffix);
    }

    private static void pump(Process process, byte[] bytes) {
        try (OutputStream os = process.getOutputStream()) {
            os.write(bytes);
        } catch (IOException e) {
            log.debug("in-box writer closed stdin early: {}", e.toString());
        }
    }

    private static void join(Thread pump) {
        try {
            pump.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for the exec to finish under the shared supervisor and returns the
     * exit code it really chose. A wait cut short by an interrupt is not one of
     * those: the tree is killed and reaped, the flag stays set for the caller
     * above, and the operation is reported as an interruption by name rather than
     * folded into an exit code (FR11).
     */
    private static int completed(Process process, String what) {
        Supervision supervision = SUPERVISOR.await(process, null);
        if (supervision.termination() != Termination.EXITED) {
            throw new UncheckedIOException(new InterruptedIOException(
                    what + " did not complete: the wait was interrupted and the exec was killed"));
        }
        return supervision.exitCode();
    }
}
