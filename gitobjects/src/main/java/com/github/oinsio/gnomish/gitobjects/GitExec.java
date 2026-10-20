package com.github.oinsio.gnomish.gitobjects;

import com.github.oinsio.gnomish.gittransfer.GitInvocation;
import com.github.oinsio.gnomish.subprocess.ChildOutput;
import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.Drain;
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.StdinSource;
import com.github.oinsio.gnomish.subprocess.Supervision;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@code git} plumbing command against a fixed {@code --git-dir}, capturing exit code,
 * stdout bytes, and stderr text, with optional stdin and a per-call stdout size cap. The only place
 * this library runs a subprocess — deliberately not the factory's {@code GitProcessRunner}, so
 * {@code gitobjects} stays import-independent of the factory (design D19). Ambient git config is
 * neutralized and pathspec magic disabled, so behavior and commit ids do not depend on the
 * operator's environment.
 *
 * <p>The command line comes from the factory's one invocation policy, {@link
 * GitInvocation#commandLine}: the binary, the policy's {@code -c} pairs (hooks, signing, the
 * filesystem monitor, maintenance and replace refs neutralized by value), then {@code --git-dir}
 * and the caller's arguments; and the policy's {@link GitInvocation#removedEnvironment()} is removed
 * from the child, so no inherited per-process configuration adds a key back (design D1, D2 of
 * own-git-invocation-policy). The global and system configuration files stay isolated here as
 * well, since this library runs only local plumbing and needs no operator setting.
 *
 * <p>The I/O policy — the stdout cap, the hermetic environment, and the loud {@link
 * GitObjectsInterruptedException} an interruption raises — is this class's own. The mechanics are the
 * dependency-free {@code :subprocess} module's (design D13 of bound-subprocess-commands, D17 of
 * fix-terminal-receipt-convergence): the command is started through the {@link ProcessLaunch} owner,
 * which hands git its stdin as a file or {@code /dev/null} (design D20), so no thread of this class
 * writes into a pipe; both output streams are read as {@link ChildOutput} on the module's {@link
 * Drain}, which never parks in a pipe (design D1, D2 of make-pipe-reads-non-parking), the cap applied
 * to the drained stdout; the wait and the kill run on the {@link ProcessSupervisor}, and the drains
 * are joined after it with the supervisor's post-exit bound, so the wait is always reached and an
 * interrupt always lands. D19's goal is untouched — {@code :subprocess} reaches nothing at all, so
 * extraction still means a folder move, of two modules instead of one.
 *
 * <p>A cut capture is judged by {@link CutCaptureException#refuses}, the one judgement every value
 * reader asks (design D15 as amended 2026-10-17; task 8.11 of fix-terminal-receipt-convergence):
 * refused when git exited with zero while a drain was cut, and on every other termination the partial
 * output stays readable with the cut appended to stderr through {@link CutCaptureException#withNotice},
 * in the words of {@link CutCaptureException#notice}. A launcher that could not exec git ({@link
 * ProcessLaunch#launcherCouldNotExec}) is the same launch failure as a binary the launch refused
 * (design D19 as amended 2026-10-17). This class throws its own {@link GitObjectsException} rather
 * than the subprocess refusal, since the library's callers classify its failures by that type.
 *
 * <p>{@code gitDir} must be absolute: git is launched with it as its working directory
 * <em>and</em> handed it as {@code --git-dir}, so a relative {@code ./.git} would resolve inside
 * itself. The compact constructor refuses it rather than resolving it — turning an operator's
 * directory into an absolute path is the argument owner's job, not a second one here (design D10
 * of fix-operator-blockers).
 *
 * <p>Implements FR25 of add-sandbox-core; FR13, FR9 of bound-subprocess-commands; FR13 of
 * fix-operator-blockers; FR16, FR17, FR18, FR19, FR21, FR23 of fix-terminal-receipt-convergence; FR3,
 * FR4, FR5 of make-pipe-reads-non-parking; FR1, FR3 of own-git-invocation-policy.
 *
 * @param gitDir the absolute git dir every command runs against
 * @param gitBinary the git executable
 * @param supervisor the wait and kill discipline, and the source of the post-exit bound
 * @param launch the one way the command is started
 */
record GitExec(Path gitDir, String gitBinary, ProcessSupervisor supervisor, ProcessLaunch launch) {

    /**
     * The post-exit kill of git's process group (design D19 of fix-terminal-receipt-convergence),
     * stateless, over the host's own {@code kill}. What it found is not logged: this library is a
     * dependency-free leaf with no log plane (design D19 of add-sandbox-core), and local plumbing
     * is not expected to leave a descendant — the sweep still ends one.
     */
    private static final ProcessGroupSweep SWEEP = new ProcessGroupSweep();

    /**
     * @throws IllegalArgumentException naming {@code gitDir} if it is not absolute (FR13 of
     *     fix-operator-blockers)
     */
    GitExec {
        if (!gitDir.isAbsolute()) {
            throw new IllegalArgumentException("git dir must be an absolute path: " + gitDir);
        }
    }

    @SuppressWarnings("ArrayRecordComponent") // captured output bytes, consumed once by the caller
    record Result(int exitCode, byte[] stdout, String stderr, boolean overflowed) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    Result run(List<String> args) {
        return run(args, null, Map.of(), -1);
    }

    /**
     * @param stdoutCap the most stdout bytes to keep, or a negative value for no cap; a stdout
     *     longer than the cap comes back as its prefix with {@link Result#overflowed()} set
     */
    Result run(List<String> args, byte @Nullable [] stdin, Map<String, String> extraEnv, long stdoutCap) {
        List<String> line = new ArrayList<>();
        line.add("--git-dir=" + gitDir);
        line.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(GitInvocation.commandLine(gitBinary, line));
        builder.directory(gitDir.toFile());
        Map<String, String> env = builder.environment();
        GitInvocation.removedEnvironment().forEach(env::remove);
        env.put("GIT_LITERAL_PATHSPECS", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.putAll(extraEnv);

        Process process;
        try {
            process = launch.start(builder, stdin == null ? StdinSource.NONE : StdinSource.of(stdin));
        } catch (IOException e) {
            throw new GitObjectsException("could not launch git binary: " + gitBinary, e);
        }

        Drain stdout = stdoutCap < 0
                ? Drain.start(ChildOutput.stdout(process), "gitexec-stdout")
                : Drain.capped(ChildOutput.stdout(process), "gitexec-stdout", stdoutCap);
        Drain stderr = Drain.start(ChildOutput.stderr(process), "gitexec-stderr");
        return await(process, stdout, stderr);
    }

    /**
     * Waits for the git subprocess, then joins both drains with the supervisor's post-exit bound.
     *
     * <p>No deadline is passed to the wait: a local plumbing command against a bare repository that
     * never returns is a broken machine, not a broken remote (NG3 of bound-subprocess-commands, NG15
     * of fix-terminal-receipt-convergence). Only the post-exit join is bounded (design D15, D17): a
     * descendant that holds git's pipes and writes nothing lets the drains end as soon as they see
     * the exit, the capture whole; one that keeps writing costs the caller the bound once,
     * after which {@link Drain#joinAll} stops the drains still open and reports them cut. No pipe is
     * closed here — the JDK's reaper closes it (design D3, D4 of make-pipe-reads-non-parking).
     *
     * <p>Once the drains are joined, git's process group is swept ({@link ProcessGroupSweep},
     * design D19 of fix-terminal-receipt-convergence) on every termination, before any refusal.
     *
     * <p>An interrupt — in the wait, where the supervisor kills the tree and restores the flag, or
     * in the join, where the drain restores it — surfaces as {@link GitObjectsInterruptedException}.
     * A cut drain is judged with Go's precedence (design D15 as amended 2026-10-17): when git exited
     * with zero the command fails with a {@link GitObjectsException} naming the stream and the bound,
     * since this library reads factory state and a prefix handed back as the whole of a blob, a tree
     * or an object id would be a silent truncation of it; when git failed, its own failure stands —
     * the exit code keeps its meaning for {@code exists()} and {@code check()}, and the cut rides the
     * stderr {@code check()} reports rather than becoming a second finding.
     */
    private Result await(Process process, Drain stdout, Drain stderr) {
        Supervision supervision = supervisor.await(process, null);
        boolean cut = Drain.joinAll(supervisor.postExitBound(), stdout, stderr);
        SWEEP.sweep(process);
        if (supervision.termination() == Termination.INTERRUPTED
                || Thread.currentThread().isInterrupted()) {
            throw new GitObjectsInterruptedException("interrupted waiting for git", new InterruptedException());
        }
        // Past the interrupt check the termination is EXITED: no deadline was passed, so no timeout.
        int exit = supervision.exitCode();
        String stderrText = stderr.captured();
        if (ProcessLaunch.launcherCouldNotExec(supervision.termination(), exit, stderrText)) {
            // The launcher started and could not exec git (design D19 as amended 2026-10-17): the
            // launch failure run() reports for a binary start() refused, never an exit code a
            // caller would read as "ref does not exist".
            throw new GitObjectsException("could not launch git binary: " + gitBinary
                    + " (the process-group launcher could not execute it, exit " + exit + ")");
        }
        if (cut) {
            String notice = cutNotice(stdout, stderr);
            if (CutCaptureException.refuses(supervision.termination(), exit, cut)) {
                throw new GitObjectsException(notice);
            }
            stderrText = CutCaptureException.withNotice(stderrText, notice);
        }
        return new Result(exit, stdout.bytes(), stderrText, stdout.overflowed());
    }

    private String cutNotice(Drain stdout, Drain stderr) {
        return "git " + CutCaptureException.notice(stdout, stderr, supervisor.postExitBound());
    }
}
