package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.gittransfer.GitInvocation;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.Drain;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * <p>Implements FR11, NFR-S4 of define-executor-contract.
 */
final class WorkingCopyCheck {

    /** The most bytes of one view the check reads; a view over it cannot be judged. */
    static final int VIEW_CAP_BYTES = 4 * 1024 * 1024;

    /** The most paths a refusal names. */
    static final int NAMED_PATHS = 5;

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

    /**
     * @param environment the environment whose working copy the run uses; materialized; never null
     * @param bound how long one view may take: the factory's startup bound, the bound of its other short
     *     read in the environment ({@code describe}); never null
     * @param clock the clock the bound is measured on; never null
     */
    WorkingCopyCheck(TaskExecutionEnvironment environment, Duration bound, InstantSource clock) {
        this.environment = environment;
        this.bound = bound;
        this.clock = clock;
    }

    /**
     * Whether a run of {@code role} must leave the working copy unchanged.
     *
     * @param role the request's role; never null
     * @return {@code true} for {@code verdict} and {@code decision}
     */
    static boolean applies(Role role) {
        return role == Role.VERDICT || role == Role.DECISION;
    }

    /**
     * Takes git's view of the working copy.
     *
     * @return the view, or why none could be taken; never null
     */
    WorkingCopyView view() {
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(STATUS, Map.of(), null, StderrPolicy.MERGED));
        } catch (ProcessStartException refused) {
            return new WorkingCopyView.Unreadable("the environment refused to start git: "
                    + UntrustedText.subprocess(String.valueOf(refused.getMessage()))
                            .excerpt(512));
        }
        Drain output = Drain.capped(handle.output(), "working-copy-view", VIEW_CAP_BYTES + 1L);
        ExecHandle.Wait wait = handle.waitForExitOrTimeout(bound, clock);
        boolean cut = Drain.joinAll(handle.postExitBound(), output);
        String text = output.captured();
        return switch (wait) {
            case ExecHandle.Wait.TimedOut ignored ->
                new WorkingCopyView.Unreadable("git status did not finish within " + bound);
            case ExecHandle.Wait.Interrupted ignored ->
                new WorkingCopyView.Unreadable("the wait for git status was interrupted");
            case ExecHandle.Wait.Exited ignored -> exited(handle.waitForExit(), text, cut || output.overflowed());
        };
    }

    private static WorkingCopyView exited(int exitCode, String text, boolean incomplete) {
        if (exitCode != 0) {
            return new WorkingCopyView.Unreadable("git status exited " + exitCode + ": "
                    + UntrustedText.subprocess(text).excerpt(512));
        }
        if (incomplete) {
            return new WorkingCopyView.Unreadable(
                    "git status wrote more than " + VIEW_CAP_BYTES + " bytes, or held its output open");
        }
        return new WorkingCopyView.Taken(StatusEntries.of(text));
    }

    /**
     * The run's result as the check lets it through: unchanged when the view after the run equals
     * {@code before}, otherwise the refusal, its answer discarded and its usage and denials kept.
     *
     * @param executor the executor's name; never blank
     * @param request the run's request; never null
     * @param before the view taken before the run; never null
     * @param result the run's result; never null
     * @return the result, or its refusal; never null
     */
    ExecutorResult judge(
            String executor, ExecutorRequest request, WorkingCopyView.Taken before, ExecutorResult result) {
        String obligation = "a " + request.role().wire() + " run must leave the working copy unchanged (policy "
                + policy(request.policy()) + ")";
        return switch (view()) {
            case WorkingCopyView.Unreadable unreadable ->
                ProgramFailures.discarded(
                        executor,
                        obligation + ", and it could not be verified after the run: " + unreadable.why(),
                        result);
            case WorkingCopyView.Taken after -> {
                List<String> changed = StatusEntries.changedPaths(before.entries(), after.entries());
                yield changed.isEmpty()
                        ? result
                        : ProgramFailures.discarded(
                                executor, obligation + "; the run changed " + named(changed), result);
            }
        };
    }

    private static String named(List<String> paths) {
        List<String> shown = new ArrayList<>();
        for (String path : paths.subList(0, Math.min(NAMED_PATHS, paths.size()))) {
            shown.add(UntrustedText.subprocess(path).excerpt(256));
        }
        String named = String.join(", ", shown);
        return paths.size() > NAMED_PATHS ? named + " and " + (paths.size() - NAMED_PATHS) + " more" : named;
    }

    static String policy(Policy policy) {
        return switch (policy) {
            case Policy.ReadOnly ignored -> "readOnly";
            case Policy.WriteExactly exactly -> "writeExactly(" + exactly.path() + ")";
            case Policy.WorkspaceWrite ignored -> "workspaceWrite";
        };
    }
}
