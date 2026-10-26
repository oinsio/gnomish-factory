package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.subprocess.ChildOutput;
import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.Drain;
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.StdinSource;
import com.github.oinsio.gnomish.subprocess.Supervision;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
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
 * <p>Kept in sync with {@link ChannelPathResolver}: both refuse a path that
 * normalizes outside the working copy and the scratch root, and both refuse
 * {@code .git/**} under the working copy, even though this class's refusal is
 * lexical-only (no symlink resolution) where the host twin resolves symlinks.
 *
 * <p>Both exec pipes are read as non-parking {@link ChildOutput}s on {@link Drain}s, concurrently
 * with the wait (design D2 of bound-subprocess-commands; D1, D2 of make-pipe-reads-non-parking): a
 * read to the end on the calling thread would let a hung in-box command hold the channel ahead of
 * any supervision. A drain ends as soon as it sees the exit with nothing pending; the drains
 * are joined with the {@link DockerCli} supervisor's post-exit bound, and one a holder still feeds
 * there is stopped — no pipe is closed (D3, D4) — and judged by {@link CutCaptureException#refuses}
 * (design D15 as amended 2026-10-17, D17 of fix-terminal-receipt-convergence).
 *
 * <p>The file content of a write reaches the in-box {@code cat} as a {@link
 * StdinSource} the launch owner turns into the {@code docker exec -i} client's
 * input file (design D20): no factory thread pumps bytes into a pipe a
 * descendant may hold. A read passes {@link StdinSource#NONE}.
 *
 * <p>The exec's wait and kill go through the shared subprocess supervisor
 * (design D11 of bound-subprocess-commands), so an interrupted channel operation
 * ends as a named {@link Termination}, reported as an {@link
 * InterruptedIOException}, never as an exit code an in-box script could choose.
 *
 * <p>Implements FR1, NFR-S3 of add-sandbox-core; FR11 of
 * bound-subprocess-commands; FR17, FR19, FR23 of fix-terminal-receipt-convergence; FR1, FR3 of
 * make-pipe-reads-non-parking; FR3, FR5 of fix-docker-exec-env-argv.
 *
 * @param docker the docker seam the channel's execs start through
 * @param launches the environment's one producer of exec launches, which also names the container
 *     key and the in-box working directory each exec runs in
 * @param workingCopy the working-copy root a path is validated against
 * @param scratch the per-environment scratch root a path is validated against
 */
record ContainerFileChannel(DockerCli docker, ContainerExecLaunches launches, Path workingCopy, Path scratch) {

    private static final Logger log = LoggerFactory.getLogger(ContainerFileChannel.class);

    // Writes to the factory-chosen path via a positional arg ($1), never string-interpolated into
    // the script — so a path can carry no shell metacharacter that alters the command.
    private static final String WRITE_SCRIPT = "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"";

    // Absent file exits 42 (distinct from any cat error) so readFile tells "no such file" (empty)
    // apart from a present file; head -c bounds output at the source, avoiding a host-side
    // read-then-kill truncation race on the exec pipe.
    private static final String READ_SCRIPT = "if [ -f \"$1\" ]; then head -c \"$2\" \"$1\"; else exit 42; fi";
    private static final int ABSENT_EXIT = 42;

    ContainerFileChannel(DockerCli docker, ContainerExecLaunches launches, String workingCopy, String scratch) {
        this(docker, launches, Path.of(workingCopy), Path.of(scratch));
    }

    void putFile(String path, byte[] content) {
        String resolved = validate(path);
        String what = "in-box write to " + path;
        ChannelExec exec = run(List.of("sh", "-c", WRITE_SCRIPT, "gnomish", resolved), StdinSource.of(content), what);
        if (exec.code() != 0) {
            throw new UncheckedIOException(failure(what, exec));
        }
    }

    Optional<byte[]> readFile(String path, long sizeCap) {
        if (sizeCap <= 0) {
            throw new IllegalArgumentException("readFile sizeCap must be positive, got " + sizeCap);
        }
        String resolved = validate(path);
        long bound = sizeCap + 1;
        String what = "in-box read of " + path;
        List<String> argv = List.of("sh", "-c", READ_SCRIPT, "gnomish", resolved, Long.toString(bound));
        ChannelExec exec = run(argv, StdinSource.NONE, what);
        if (exec.code() == ABSENT_EXIT) {
            return Optional.empty();
        }
        if (exec.code() != 0) {
            throw new UncheckedIOException(failure(what, exec));
        }
        byte[] bytes = exec.stdout().bytes();
        if (bytes.length > sizeCap) {
            log.warn(
                    OperatorEvent.CONTAINER_CHANNEL_FILE_TRUNCATED.head()
                            + "channel file {} in {} exceeded read cap {} bytes; truncated",
                    path,
                    launches.key(),
                    sizeCap);
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
    private static IOException failure(String what, ChannelExec exec) {
        String detail = exec.stderr().strip();
        String suffix = detail.isEmpty() ? "" : "; stderr: " + detail;
        return new IOException(what + " failed with exit " + exec.code() + suffix);
    }

    /**
     * Starts the exec, drains both pipes on the owner's {@link Drain} concurrently
     * with the supervised wait, and joins them under the supervisor's post-exit
     * bound (design D15, D17 of fix-terminal-receipt-convergence). The exec is
     * built by the environment's {@link ContainerExecLaunches} with an empty map — a
     * channel exec forwards no variable, so its argv carries no {@code -e} (FR3, FR5 of
     * fix-docker-exec-env-argv). The {@code -i} flag follows from {@code stdin} inside
     * {@link DockerCommands#exec}, never chosen beside it (D20).
     *
     * <p>An interrupted wait kills and reaps the tree and leaves the flag set; the
     * flag is read after the joins, so a join it cut short fails the operation too
     * rather than handing back a prefix (FR11 of bound-subprocess-commands). A drain stopped at the
     * bound is judged by {@link CutCaptureException#refuses}: after a clean exit
     * with code zero the operation fails, the stream named in {@link
     * CutCaptureException#notice}; otherwise the cut rides the failure's stderr.
     */
    private ChannelExec run(List<String> argv, StdinSource stdin, String what) {
        Process process = docker.start(launches.launch(Map.of(), stdin, argv), StderrPolicy.PIPED, stdin);
        Drain stdout = Drain.start(ChildOutput.stdout(process), "channel-stdout");
        Drain stderr = Drain.start(ChildOutput.stderr(process), "channel-stderr");
        ProcessSupervisor supervisor = docker.supervisor();
        Supervision supervision = supervisor.await(process, null);
        Duration bound = supervisor.postExitBound();
        boolean cut = Drain.joinAll(bound, stdout, stderr);
        HostSweep.after(process, what); // D19: the docker client's group, on every termination
        if (supervision.termination() != Termination.EXITED
                || Thread.currentThread().isInterrupted()) {
            throw new UncheckedIOException(new InterruptedIOException(
                    what + " did not complete: the wait was interrupted and the exec was killed"));
        }
        int code = supervision.exitCode();
        IOException broken = stdout.failure();
        if (broken != null) {
            // A stopped drain fails nothing (no pipe is closed, D3): a failure is a stream that broke.
            throw new UncheckedIOException("could not read " + what + " output", broken);
        }
        String diagnostics = stderr.captured();
        if (cut) {
            String notice = CutCaptureException.notice(stdout, stderr, bound);
            if (CutCaptureException.refuses(Termination.EXITED, code, cut)) {
                throw new UncheckedIOException(what + " " + notice, new IOException(notice));
            }
            diagnostics = CutCaptureException.withNotice(diagnostics, notice);
        }
        return new ChannelExec(code, stdout, diagnostics);
    }

    // One finished exec: the code it chose, its joined stdout drain, and its stderr as diagnostics.
    private record ChannelExec(int code, Drain stdout, String stderr) {}
}
