package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host {@link TaskExecutionEnvironment} adapter (design D1, D20, FR2):
 * implements the port over the existing worktree + {@link ProcessBuilder}
 * mechanics, and is the sole process-launch seam in host mode — the behavior
 * {@code AgentProcessLauncher} and {@code CommandProcessRunner} spawned directly
 * before this change. Its passport declares no isolation (FR2): the env
 * allowlist bounds environment variables only, never filesystem access.
 *
 * <p>Isolation mechanics stay host-shaped: {@link #materialize} adopts a
 * pre-supplied working copy and allocates a factory-private scratch directory
 * outside it, {@link #exec} runs a local subprocess with the working copy as
 * cwd, {@link #harvest} is a no-op (the branch is already in the factory clone),
 * and {@link #dispose} removes the scratch area.
 *
 * <p>The child environment of every {@link #exec} is the layered positive
 * allowlist of D6 — nothing inherited implicitly: {@link
 * ProcessBuilder#environment()} is cleared, then filled with the composition of
 * the fixed documented host base set ({@link #BASE_ENV_NAMES} — deliberately no
 * agent sockets such as {@code SSH_AUTH_SOCK}), the operator's passthrough
 * names with values read live from the factory environment, and the command's
 * factory-set {@code env} fragment ({@link ChildEnvAllowlist}). This replaces
 * the pre-change inherit-everything-minus-scrub behavior at the same single
 * {@link ProcessBuilder} start seam.
 *
 * <p>Implements FR1, FR2, FR4, FR9, NFR-S3 of add-sandbox-core.
 */
public final class HostTaskExecutionEnvironment implements TaskExecutionEnvironment {

    private static final Logger log = LoggerFactory.getLogger(HostTaskExecutionEnvironment.class);

    /**
     * The fixed documented host base set (D6, FR9): enough that a typical
     * project needs zero env configuration — {@code PATH} alone resolves most
     * toolchains — while agent sockets and every unrelated cloud key stay out.
     * Locale is the three variables POSIX tools commonly honor.
     */
    public static final List<String> BASE_ENV_NAMES =
            List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "USER", "SHELL");

    private final Path workingCopy;
    private final Clock clock;
    private final ChildEnvAllowlist allowlist;

    private @Nullable Path scratch;
    private @Nullable ChannelPathResolver channels;

    /**
     * @param workingCopy the pre-materialized working copy the processes run in;
     *     never null
     * @param clock the read-time source stamped onto each {@link ExecHandle}'s
     *     start instant; never null
     * @param allowlist the layered child-environment allowlist every exec child
     *     is composed from (D6, FR9); never null — {@link
     *     ChildEnvAllowlist#none()} when neither passthrough nor a tracker is
     *     involved
     */
    public HostTaskExecutionEnvironment(Path workingCopy, Clock clock, ChildEnvAllowlist allowlist) {
        this.workingCopy = workingCopy;
        this.clock = clock;
        this.allowlist = allowlist;
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        if (!Files.isDirectory(workingCopy)) {
            throw new IllegalStateException("host working copy is not a directory: " + workingCopy);
        }
        log.debug("host environment materialized on branch {} (pin {})", branch, commitPin);
        try {
            scratch = Files.createTempDirectory("gnomish-scratch-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not allocate host scratch area", e);
        }
        channels = ChannelPathResolver.of(workingCopy, scratch);
    }

    @Override
    public ExecHandle exec(ExecCommand command) {
        ProcessBuilder builder = new ProcessBuilder(command.command());
        builder.directory(workingCopy.toFile());
        builder.redirectErrorStream(command.mergeStderr());
        // Positive allowlist, nothing inherited (D6, FR9): clear the inherited factory
        // environment, then put exactly the composed base ∪ passthrough ∪ factory-set map.
        builder.environment().clear();
        builder.environment().putAll(allowlist.compose(BASE_ENV_NAMES, command.env()));
        Process process = start(builder, command.command());
        Instant startedAt = clock.now();
        ChildProcessStdin.feed(process, command.stdin());
        return new HostExecHandle(process, startedAt);
    }

    private static Process start(ProcessBuilder builder, List<String> command) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ProcessStartException("could not start process: " + command.getFirst(), e);
        }
    }

    @Override
    public void putFile(String path, byte[] content) {
        HostChannelFiles.putFile(channels(), path, content);
    }

    @Override
    public Optional<byte[]> readFile(String path, long sizeCap) {
        return HostChannelFiles.readFile(channels(), path, sizeCap, log);
    }

    @Override
    public void harvest() {
        // Host mode: the task branch is already in the factory clone — harvest is a no-op (FR5).
    }

    @Override
    public void dispose() {
        Path toRemove = scratch;
        if (toRemove == null) {
            return;
        }
        scratch = null;
        channels = null;
        HostChannelFiles.deleteRecursively(toRemove, log);
    }

    @Override
    public String scratchRoot() {
        return requireScratch().toString();
    }

    @Override
    public CapabilityPassport passport() {
        return CapabilityPassport.hostNoIsolation();
    }

    private ChannelPathResolver channels() {
        ChannelPathResolver c = channels;
        if (c == null) {
            throw new IllegalStateException("environment not materialized: file channel unavailable");
        }
        return c;
    }

    private Path requireScratch() {
        Path s = scratch;
        if (s == null) {
            throw new IllegalStateException("environment not materialized: scratch area unavailable");
        }
        return s;
    }
}
