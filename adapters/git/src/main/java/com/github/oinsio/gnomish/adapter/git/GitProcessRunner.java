package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.CaptureRunner;
import com.github.oinsio.gnomish.subprocess.Captured;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.SequencedMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shells out to the real {@code git} binary via {@code ProcessBuilder} — no JGit (ADR 0001) —
 * invoking each command as a direct argv (e.g. {@code ProcessBuilder("git", "status")}), never
 * via a shell, so taskIds and other user-controlled strings that later flow into git args (e.g.
 * branch names) carry no shell-quoting or injection risk. Every invocation runs with the current
 * process's inherited environment (no global git config assumptions) minus git's interactive
 * credential prompting, which is switched off so no command can block on an unanswerable password
 * question, and a caller-supplied working directory, which later tasks use to target the worktree root (design D11, D12, D3).
 *
 * <p>Public (rather than package-private) so the {@code app} layer's git-mode wiring
 * (design D8 of add-git-workflow, task 4.4 onward) can construct the one instance shared by a
 * run's {@link TaskBranchCreator}, {@link TaskWorktreeManager}, {@link GitTaskRepository}, and
 * {@link GitAttemptPersistence} — the same "one instance per run" idiom already relied on within
 * this package's own specs.
 *
 * <p>Repo-level mutating commands — {@code fetch}, {@code push}, {@code worktree add/remove/prune}
 * — are additionally serialized per target clone (design D8, NFR-R2 of add-factory-serve): {@link
 * #run} classifies every call by its subcommand and, for a mutating one, resolves the clone the
 * command actually shares a {@code .git} directory with (via {@code git rev-parse
 * --git-common-dir}, so a push or fetch issued with {@code cwd} inside a linked worktree still
 * maps back to the owning clone rather than being keyed by the worktree's own, per-task path) and
 * runs it under a {@link CloneMutationLock} instance shared across all {@link GitProcessRunner}s
 * in this process — see {@link CloneMutationLock} for why a shared instance matters here. Read-only
 * commands and in-worktree working-tree operations (e.g. {@code reset}, {@code clean}, {@code
 * branch}, {@code rev-parse}, {@code worktree list}) run unlocked, in parallel.
 *
 * <p>A transfer — a fetch or a clone — enters only as the owner's {@link GitTransfer} value, through
 * {@link #run(Path, GitTransfer)}: the value's environment entries are applied to the child (an
 * allowlist set, an inherited per-process configuration removed) and its argv runs through the same
 * path as every other command. The untyped {@link #run(Path, String...)} refuses a transfer
 * subcommand before any process launches, so a hand-built fetch cannot bypass the owner (FR8,
 * design D5 of own-git-transfer-argv).
 *
 * <p>Commands that reach a remote (see {@link GitNetworkCommands}) are the ones this runner bounds:
 * they carry git's own stall detection and, as a backstop for a process that is wedged rather than
 * merely slow, a hard deadline enforced by the shared subprocess supervisor — output drained
 * concurrently, the whole process tree killed on expiry, and a named {@code TIMED_OUT} outcome
 * instead of an exit code nobody can distinguish from git's own. Local commands stay unbounded
 * (NG3): a {@code commit} or a {@code rev-parse} that hangs is a broken machine, not a broken
 * remote. An interrupted wait is likewise a named outcome, not exit {@code -1} (design D1, D6).
 *
 * <p>Every capture leaves this runner as {@link UntrustedText} through
 * {@link GitCommandResult#of}, which is also where stderr passes through {@link CredentialScrub},
 * so a remote URL's embedded credentials cannot reach an operator log or a tracker-published
 * report through any of this package's call sites (NFR-S2 of fix-lifecycle-push) — including the
 * partial stderr of a command killed on its deadline.
 *
 * <p>It is a parser of one answer of its own: {@code rev-parse --git-common-dir}, whose standard
 * output it turns into the {@link Path} the mutation lock keys on (design D11 of
 * type-untrusted-text). Nothing else here reads captured text.
 *
 * <p>A bound that fires says so once, here: the runner is the only place that knows both how long
 * the command actually ran and what deadline it was given, so the WARN naming the command class,
 * the elapsed time and the configured deadline belongs to it (NFR-O1). Callers add their own line
 * about what the command was for.
 *
 * <p>Implements FR2 of add-git-workflow; NFR-S2 of fix-lifecycle-push; FR1, FR2, FR4, FR5, FR6,
 * NFR-O1, NFR-O2, NFR-S2 of bound-subprocess-commands; FR10 of type-untrusted-text; FR6, FR8 of
 * own-git-transfer-argv.
 */
@UntrustedParser
public final class GitProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(GitProcessRunner.class);

    /**
     * The documented default for a network command (FR5): long enough that a real clone of a real
     * repository finishes under it, short enough that a dead remote does not hold a run for the
     * rest of the night. Written as five minutes, which is the 300 s the proposal documents. An
     * installation raises it through {@code factory.git-network-timeout}.
     */
    static final Duration DEFAULT_NETWORK_TIMEOUT = Duration.ofMinutes(5);

    // Shared across every GitProcessRunner instance in this process (not per-instance): call
    // sites throughout this package each construct their own GitProcessRunner, so the lock scope
    // must live above any one instance for concurrent factory slots to serialize correctly against
    // the same clone (design D8).
    private static final CloneMutationLock MUTATION_LOCK = new CloneMutationLock();

    private final String gitBinary;
    private final Duration networkTimeout;
    private final CaptureRunner captureRunner = new CaptureRunner();

    public GitProcessRunner() {
        this("git");
    }

    public GitProcessRunner(String gitBinary) {
        this(gitBinary, DEFAULT_NETWORK_TIMEOUT);
    }

    /**
     * The {@code git} on {@code PATH} under an explicit network deadline — what the composition
     * root hands the installation's {@code factory.git-network-timeout} through (FR5, design D8 of
     * bound-subprocess-commands).
     *
     * @param networkTimeout the hard bound on a command that reaches a remote
     */
    public GitProcessRunner(Duration networkTimeout) {
        this("git", networkTimeout);
    }

    /**
     * @param gitBinary the git executable to invoke
     * @param networkTimeout the hard bound on a command that reaches a remote; the composition
     *     root passes the installation's {@code factory.git-network-timeout}, and specs inject a
     *     sub-second one
     */
    public GitProcessRunner(String gitBinary, Duration networkTimeout) {
        this.gitBinary = gitBinary;
        this.networkTimeout = networkTimeout;
    }

    /**
     * Runs {@code git <args...>} with {@code cwd} as the working directory, capturing stdout and
     * stderr as separate UTF-8 strings, the exit code, and how the invocation ended. A non-zero git
     * exit code is returned in the result, never thrown — callers decide what a given command's
     * exit code means, after reading {@link GitCommandResult#termination()} first.
     *
     * <p>Repo-level mutating commands (see class javadoc) serialize per target clone; every other
     * command runs immediately, unlocked. Network commands are bounded by the configured deadline;
     * local ones are not.
     *
     * @param cwd the working directory for the git process
     * @param args the git subcommand and its arguments, e.g. {@code "status"} or {@code "init",
     *     "--bare"}
     * @return the captured exit code, separate stdout/stderr, and the named termination
     * @throws GitBinaryNotFoundException if the configured git executable could not be launched
     *     at all (missing from {@code PATH}, not executable, ...)
     * @throws UnownedTransferException if {@code args} names a transfer subcommand, which only the
     *     owner's value may carry (see {@link #run(Path, GitTransfer)})
     */
    GitCommandResult run(Path cwd, String... args) {
        if (GitNetworkCommands.isTransfer(args)) {
            throw new UnownedTransferException(GitNetworkCommands.subcommand(args));
        }
        return runClassified(cwd, args, Collections.emptySortedMap());
    }

    /**
     * Runs the owner's transfer value: its environment entries applied to the child — a present
     * value set, an empty one removed, on top of what every command gets — and its argv executed
     * under the same rules as {@link #run(Path, String...)}: a fetch serializes per clone, both
     * kinds are network commands and so bounded and stall-detected, stderr scrubbed (FR6, FR8 of
     * own-git-transfer-argv).
     *
     * @param cwd the working directory for the git process: the clone for a fetch, the parent of
     *     the destination for a clone
     * @param transfer the owner's value
     * @return the captured exit code, separate stdout/stderr, and the named termination
     * @throws GitBinaryNotFoundException if the configured git executable could not be launched
     */
    GitCommandResult run(Path cwd, GitTransfer transfer) {
        return runClassified(cwd, transfer.argv().toArray(String[]::new), transfer.environment());
    }

    private GitCommandResult runClassified(
            Path cwd, String[] args, SequencedMap<String, Optional<String>> transferEnvironment) {
        if (!cwd.toFile().isDirectory()) {
            return GitCommandResult.of(128, "", "fatal: cwd does not exist: " + cwd);
        }

        if (!isRepoLevelMutating(args)) {
            return execute(cwd, args, transferEnvironment);
        }

        Path cloneKey = resolveCloneKey(cwd);
        return MUTATION_LOCK.runLocked(cloneKey, () -> execute(cwd, args, transferEnvironment));
    }

    /**
     * Classifies {@code args} as a repo-level mutating command per design D8: {@code fetch},
     * {@code push}, and {@code worktree add|remove|prune} — the operations that write into a
     * clone's shared object database, refs, or worktree registry. Everything else (read-only
     * queries such as {@code rev-parse}/{@code worktree list}, and in-worktree operations such as
     * {@code branch}, {@code reset}, {@code clean}, {@code commit}, which only touch one worktree's
     * own index/working tree or a single, independently-locked ref) is left unlocked. Leading
     * {@code -c key=value} global-option pairs are skipped before classifying, so a fetch that
     * carries per-invocation config (the owner's {@code -c} pairs precede every transfer's
     * subcommand) still serializes like any other fetch — the same skip the network
     * classification uses.
     */
    private static boolean isRepoLevelMutating(String... args) {
        int i = GitNetworkCommands.subcommandIndex(args);
        if (i >= args.length) {
            return false;
        }
        return switch (args[i]) {
            case "fetch", "push" -> true;
            case "worktree" ->
                args.length > i + 1
                        && (args[i + 1].equals("add") || args[i + 1].equals("remove") || args[i + 1].equals("prune"));
            default -> false;
        };
    }

    /**
     * Resolves the clone that a mutating command's {@code cwd} actually shares a {@code .git}
     * directory with, via the read-only {@code git rev-parse --git-common-dir}: for the clone's
     * own directory this is (relative) {@code .git}; for a linked worktree, git resolves it back
     * to the owning clone's {@code .git}, which is exactly the shared resource this lock protects
     * (a push or fetch run with {@code cwd} inside a worktree still writes into the clone's shared
     * refs/object database). Falls back to {@code cwd} itself, canonicalized, if the resolution
     * command fails (e.g. {@code cwd} is not actually a git repository) — a degraded-but-safe key
     * that at worst under-serializes against a real clone it cannot identify.
     */
    private Path resolveCloneKey(Path cwd) {
        GitCommandResult commonDir = execute(cwd, "rev-parse", "--git-common-dir");
        if (commonDir.exitCode() != 0) {
            return canonicalize(cwd);
        }
        Path resolved = Path.of(commonDir.stdout().forParsing().trim());
        Path gitCommonDir = resolved.isAbsolute() ? resolved : cwd.resolve(resolved);
        return canonicalize(gitCommonDir);
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private GitCommandResult execute(Path cwd, String... args) {
        return execute(cwd, args, Collections.emptySortedMap());
    }

    private GitCommandResult execute(
            Path cwd, String[] args, SequencedMap<String, Optional<String>> transferEnvironment) {
        boolean network = GitNetworkCommands.isNetwork(args);
        ProcessBuilder builder =
                new ProcessBuilder(commandLine(network ? GitNetworkCommands.withStallDetection(args) : args));
        builder.directory(cwd.toFile());
        // Pin git's message locale for the child process only: callers classify failures by
        // stderr content (e.g. the harvest fetch's non-fast-forward refusal, FR5 of
        // add-sandbox-core), and a localized git would defeat that parsing.
        builder.environment().put("LC_ALL", "C");
        // Never let a network command block on a credential prompt (NFR-R2): the factory runs git
        // with no controlling terminal in `take`/`serve`, but a `gnomish run` inherits the
        // operator's TTY, and there a push to an origin whose token expired would sit forever
        // waiting for a password nobody is going to type — an unattended run hung, not failed.
        // GIT_TERMINAL_PROMPT=0 turns that wait into an immediate failure the caller's exit-code
        // handling already covers; the two askpass hooks are emptied because they are consulted
        // BEFORE the terminal and would otherwise reopen the same indefinite wait through a helper.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");
        builder.environment().put("SSH_ASKPASS", "");
        if (network) {
            GitNetworkCommands.applySshStallDetection(builder.environment());
        }
        // Last, so the owner's entries are what the child sees; none of them names a variable set
        // above — the owner strips configuration and sets the protocol allowlist, nothing else.
        GitNetworkCommands.applyTransferEnvironment(builder.environment(), transferEnvironment);

        Duration deadline = network ? networkTimeout : null;
        long startedAt = System.nanoTime();
        Captured captured = capture(builder, deadline);
        report(captured.termination(), args, deadline, Duration.ofNanos(System.nanoTime() - startedAt));
        // The single choke point for git's text (design D3 of type-untrusted-text): both streams
        // become UntrustedText here, at the first factory reader, and stderr is scrubbed of any
        // remote-URL credentials on the way — before a caller can log it or carry it into a report
        // a tracker publishes, the partial stderr of a killed command included, since a timed-out
        // push is exactly where a credential-bearing URL tends to be half-printed. Both are
        // GitCommandResult.of's business now, so a result built anywhere else is scrubbed too.
        return GitCommandResult.of(captured.exitCode(), captured.stdout(), captured.stderr(), captured.termination());
    }

    /**
     * Logs the one WARN a bound that fired owes an operator (NFR-O1, NFR-O2): which class of
     * command ended early, how long it ran, and — for a timeout — the deadline they would raise to
     * give it more. Only the subcommand is named, never the full argv: a {@code clone} or a
     * {@code push} argument can carry a credential-bearing remote URL, and this line is written
     * before any scrub could reach it.
     */
    private void report(Termination termination, String[] args, @Nullable Duration deadline, Duration elapsed) {
        if (termination == Termination.EXITED) {
            return;
        }
        String subcommand = GitNetworkCommands.subcommand(args);
        if (termination == Termination.TIMED_OUT) {
            log.warn(
                    OperatorEvent.GIT_NETWORK_COMMAND_TIMED_OUT.head()
                            + "git network command timed out and its process tree was killed: subcommand={},"
                            + " elapsed={}, deadline={}",
                    subcommand,
                    elapsed,
                    deadline);
            return;
        }
        // FR9 of harden-logging-observability: an interrupt during the shutdown phase is the stop
        // doing its job, so the line says so rather than reading as an unexplained abort. The level
        // stays WARN and the stack stays absent either way — the bound that fired is the whole fact.
        log.warn(
                OperatorEvent.GIT_COMMAND_KILLED.head()
                        + "git command {} and its process tree was killed: subcommand={}, elapsed={}",
                ShutdownPhase.inProgress() ? "interrupted by the daemon shutdown" : "interrupted",
                subcommand,
                elapsed);
    }

    /**
     * Runs {@code builder} under the shared subprocess supervisor: both streams drained
     * concurrently with the process (so neither a full pipe nor a child holding stdout open can
     * block the wait), the wait bounded by {@code deadline} when there is one, and on expiry or
     * interruption the whole process tree terminated and reaped before the outcome comes back
     * (FR2, FR3, FR6). Without the kill an interrupted caller — a shutdown, a revoked claim —
     * would leave the git child running unattended with its pipes half-read; for a mutating
     * command that means a push or fetch still writing into the clone after the run that owns it
     * has gone.
     */
    private Captured capture(ProcessBuilder builder, @Nullable Duration deadline) {
        try {
            return captureRunner.run(builder, deadline);
        } catch (IOException e) {
            throw new GitBinaryNotFoundException(gitBinary, e);
        }
    }

    private String[] commandLine(String... args) {
        String[] commandLine = new String[args.length + 1];
        commandLine[0] = gitBinary;
        System.arraycopy(args, 0, commandLine, 1, args.length);
        return commandLine;
    }
}
