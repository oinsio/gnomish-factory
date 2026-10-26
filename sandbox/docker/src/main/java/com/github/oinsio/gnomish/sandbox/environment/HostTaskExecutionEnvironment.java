package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import com.github.oinsio.gnomish.subprocess.StdinSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
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
 * <p>The child is started by the {@link ProcessLaunch} handed in at construction — the one launch
 * owner, which also sets the child's standard input (design D16, D20 of
 * fix-terminal-receipt-convergence): the command's stdin is converted once, here, into the whole
 * input as a file or into nothing, so no thread of this adapter writes into a pipe a descendant of
 * the child could hold.
 *
 * <p>Implements FR1, FR2, FR4, FR9, NFR-S3 of add-sandbox-core; FR18, FR23 of
 * fix-terminal-receipt-convergence.
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

    /**
     * The {@code PATH} a host child's environment carries under {@code allowlist} — the value the
     * launch owner resolves the child's first word on — composed as {@link #exec} composes it,
     * before any per-command variable (FR1, FR4 of add-doctor-command).
     *
     * @return the child's {@code PATH}, or {@code null} when none resolves
     */
    public static @Nullable String childPath(ChildEnvAllowlist allowlist) {
        return allowlist.compose(BASE_ENV_NAMES, Map.of()).get("PATH");
    }

    private final Path workingCopy;
    private final InstantSource clock;
    private final ChildEnvAllowlist allowlist;
    private final ProcessLaunch launch;

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
     * @param launch the launch owner every exec child is started through, with its input set from
     *     the command's stdin ({@link ProcessLaunch#grouped()} in production; a spec hands in a fake
     *     process through it); never null
     */
    public HostTaskExecutionEnvironment(
            Path workingCopy, InstantSource clock, ChildEnvAllowlist allowlist, ProcessLaunch launch) {
        this.workingCopy = workingCopy;
        this.clock = clock;
        this.allowlist = allowlist;
        this.launch = launch;
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
        command.stderr().applyTo(builder);
        // Positive allowlist, nothing inherited (D6, FR9): clear the inherited factory
        // environment, then put exactly the composed base ∪ passthrough ∪ factory-set map.
        builder.environment().clear();
        builder.environment().putAll(allowlist.compose(BASE_ENV_NAMES, command.env()));
        String input = command.stdin();
        StdinSource stdin = input == null ? StdinSource.NONE : StdinSource.of(input.getBytes(StandardCharsets.UTF_8));
        Process process = start(builder, stdin, command.command());
        Instant startedAt = clock.instant();
        return new HostExecHandle(process, startedAt);
    }

    private Process start(ProcessBuilder builder, StdinSource stdin, List<String> command) {
        try {
            return launch.start(builder, stdin);
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
