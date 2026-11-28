package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.gittransfer.GitInvocation;
import com.github.oinsio.gnomish.sandbox.BoundedCapture;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The working-copy-unchanged check of the {@code verdict} and {@code decision} roles (FR11, NFR-S4
 * of define-executor-contract; design D6): the enforcement level an executor declares is not
 * trusted, so the factory takes git's own view of the working copy before and after the run, through
 * the environment's process seam — inside the box when the run is in one — and refuses the run's
 * answer when the two differ: {@code failed/infrastructure} naming the violated policy and the paths.
 * A view that cannot be taken is the same refusal, saying so: an unverified answer is never passed on.
 *
 * <p>The view is {@code git status --porcelain=v2 -z --branch --untracked-files=all
 * --ignore-submodules=none}, run under the factory's git invocation policy ({@link GitInvocation}: no
 * hook, no filesystem monitor, no inherited per-process configuration) with {@code
 * core.fileMode=true} at command scope and {@code --no-optional-locks}, so the check neither runs a
 * program the working copy's configuration names nor rewrites the index. It sees: a tracked file
 * changed, deleted or with its mode changed; a new untracked file, each one by path; a staged change;
 * a rename; a commit or a branch switch (the {@code branch.oid} and {@code branch.head} headers); a
 * submodule moved. It does not see: a path the working copy's ignore rules cover — including rules
 * the run itself writes into {@code .git/info/exclude} — anything else under {@code .git} (refs, the
 * configuration, hooks), a further edit of a path already changed before the run (its status line
 * is the same), and an index flag ({@code assume-unchanged}, {@code skip-worktree}) that hides a
 * file's change from git. A run outside a git working copy cannot be checked at all, and is refused.
 *
 * <p>A {@code program} run and an agent round are checked alike, by this one check: the agent round
 * runner's {@code verdict} and {@code decision} rounds take it too (NFR-S2 of
 * extract-agent-round-runner), and a refusal names the executor by its {@link ProgramFailures.Subject}.
 *
 * <p>Implements FR11, NFR-S4 of define-executor-contract; FR20 of migrate-verify-to-executor-contract;
 * FR1, NFR-S2 of extract-agent-round-runner.
 */
public final class WorkingCopyCheck {

    /** The most bytes of one view the check reads; a view over it cannot be judged. */
    static final int VIEW_CAP_BYTES = 4 * 1024 * 1024;

    private static final List<String> STATUS = GitInvocation.commandLine(
            "git",
            List.of(
                    "-c",
                    "core.fileMode=true",
                    "--no-optional-locks",
                    "status",
                    "--porcelain=v2",
                    "-z",
                    "--branch",
                    "--untracked-files=all",
                    "--ignored=no",
                    "--ignore-submodules=none"));

    private final TaskExecutionEnvironment environment;
    private final Duration bound;
    private final InstantSource clock;
    private final LastRegisteredCommit registered;

    /**
     * @param environment the environment whose working copy the run uses; materialized; never null
     * @param bound how long one view may take: the factory's startup bound; never null
     * @param clock the clock the bound is measured on; never null
     * @param registered the run's round cell: a move of {@code HEAD} onto its last registered commit is
     *     the factory's pending commit ({@link FactoryMove}, D21 of migrate-verify-to-executor-contract)
     */
    public WorkingCopyCheck(
            TaskExecutionEnvironment environment,
            Duration bound,
            InstantSource clock,
            LastRegisteredCommit registered) {
        this.environment = environment;
        this.bound = bound;
        this.clock = clock;
        this.registered = registered;
    }

    /**
     * Whether a run of {@code role} must leave the working copy unchanged.
     *
     * @param role the request's role; never null
     * @return {@code true} for {@code verdict} and {@code decision}
     */
    public static boolean applies(Role role) {
        return role == Role.VERDICT || role == Role.DECISION;
    }

    /**
     * Takes git's view of the working copy.
     *
     * @return the view, or why none could be taken; never null
     */
    public WorkingCopyView view() {
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(STATUS, Map.of(), null, StderrPolicy.MERGED));
        } catch (ProcessStartException refused) {
            return new WorkingCopyView.Unreadable("the environment refused to start git: "
                    + UntrustedText.subprocess(String.valueOf(refused.getMessage()))
                            .excerpt(512));
        }
        BoundedCapture output = BoundedCapture.of(handle, "working-copy-view", VIEW_CAP_BYTES + 1L, bound, clock);
        return switch (output.ended()) {
            case ExecHandle.Wait.TimedOut ignored ->
                new WorkingCopyView.Unreadable("git status did not finish within " + bound);
            case ExecHandle.Wait.Interrupted ignored ->
                new WorkingCopyView.Unreadable("the wait for git status was interrupted");
            case ExecHandle.Wait.Exited ignored -> exited(handle.waitForExit(), output.output(), output.incomplete());
        };
    }

    /** The {@code state.json} object of {@code commit}, read in the environment; empty unless answered. */
    Optional<String> stateBlobAt(String commit) {
        List<String> argv = GitInvocation.commandLine(
                "git", List.of("rev-parse", "--verify", "--quiet", commit + ":" + EnvelopePaths.STATE_JSON_PATH));
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(argv, Map.of(), null, StderrPolicy.MERGED));
        } catch (ProcessStartException refused) {
            // No lag recognised: the difference refuses the answer, naming it.
            return Optional.empty();
        }
        BoundedCapture output = BoundedCapture.of(handle, "working-copy-state-blob", 256L, bound, clock);
        boolean answered = output.ended() instanceof ExecHandle.Wait.Exited && handle.waitForExit() == 0;
        return answered && !output.incomplete() ? StatusEntries.objectId(output.output()) : Optional.empty();
    }

    private static WorkingCopyView exited(int exitCode, UntrustedText text, boolean incomplete) {
        if (exitCode != 0) {
            return new WorkingCopyView.Unreadable("git status exited " + exitCode + ": " + text.excerpt(512));
        }
        if (incomplete) {
            return new WorkingCopyView.Unreadable(
                    "git status wrote more than " + VIEW_CAP_BYTES + " bytes, or held its output open");
        }
        return new WorkingCopyView.Taken(StatusEntries.of(text));
    }

    /**
     * The run's result as the check lets it through: unchanged when the view after the run equals
     * {@code before}, otherwise the refusal ({@link WorkingCopyRefusal}), its answer discarded and its
     * usage and denials kept.
     *
     * @param subject what kind of executor ran; never null
     * @param executor the executor's name; never blank
     * @param request the run's request; never null
     * @param before the view taken before the run; never null
     * @param result the run's result; never null
     * @return the result, or its refusal; never null
     */
    public ExecutorResult judge(
            ProgramFailures.Subject subject,
            String executor,
            ExecutorRequest request,
            WorkingCopyView.Taken before,
            ExecutorResult result) {
        var refusal = new WorkingCopyRefusal(subject, executor, request);
        return switch (view()) {
            case WorkingCopyView.Unreadable unreadable -> refusal.unverified(unreadable, result);
            case WorkingCopyView.Taken after -> {
                List<String> changed = FactoryMove.changed(
                        before.entries(), after.entries(), registered.lastRegistered(), this::stateBlobAt);
                yield changed.isEmpty() ? result : refusal.changed(changed, result);
            }
        };
    }
}
