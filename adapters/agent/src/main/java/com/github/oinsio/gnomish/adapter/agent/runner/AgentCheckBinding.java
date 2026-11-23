package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.program.GuardedExecutor;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramFailures;
import com.github.oinsio.gnomish.app.port.check.BoundCheck;
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource;
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The check half of an agent registry entry (FR14, FR15 of extract-agent-round-runner; design D2,
 * D12, D13): the {@link AgentRoundRunner} of one check request, bound to the environment the request
 * runs in and behind {@link GuardedExecutor}, the one owner of the guard probe. In a run that has
 * fresh boxes the request runs in a box of its own from the run's {@link FreshBoxSource},
 * materialized at the round's attempt commit, its guard admitting the operator's sandbox egress and
 * the hosts the dialect's describe declares; in a run that has none it runs in a host environment over
 * the check's working copy — the judge's one stated exemption from "a verdict runs in a fresh box"
 * (D2), which {@code ProgramCheckPlacement} keeps refusing for a law's {@code program}. Either way the
 * check round releases the environment when the request ends.
 *
 * <p>A working copy the placement cannot use, or a box that cannot be materialized, binds an executor
 * answering {@code failed/infrastructure} naming why, with no process spawned — a cannot-verify the
 * operator sees, never an exception out of the check round.
 *
 * <p>Implements FR14, FR15 of extract-agent-round-runner.
 */
public final class AgentCheckBinding {

    /** The branch a host check environment names when it is materialized: the host ignores it. */
    static final String HOST_BRANCH = "host-check";

    private final AgentRunners<?> runners;
    private final Placement placement;

    private AgentCheckBinding(AgentRunners<?> runners, Placement placement) {
        this.runners = runners;
        this.placement = placement;
    }

    /**
     * The binding of a run that has fresh boxes: a box per request.
     *
     * @param runners the entry's runner construction; never null
     * @param boxes the run's fresh boxes; never null
     * @return the binding; never null
     */
    public static AgentCheckBinding inFreshBoxes(AgentRunners<?> runners, FreshBoxSource boxes) {
        return new AgentCheckBinding(runners, new InFreshBoxes(boxes));
    }

    /**
     * The binding of a run that has no boxes: a host environment over the check's working copy.
     *
     * @param runners the entry's runner construction; never null
     * @param childEnv the run's child-environment allowlist every host child is composed from; never null
     * @param launch the launch owner of the host children ({@link ProcessLaunch#grouped()} in
     *     production); never null
     * @return the binding; never null
     */
    public static AgentCheckBinding onHost(AgentRunners<?> runners, ChildEnvAllowlist childEnv, ProcessLaunch launch) {
        return new AgentCheckBinding(runners, new OnHost(childEnv, launch));
    }

    /**
     * The executor of one check request, in an environment of its own, and what releases it.
     *
     * @param workspace the check's working copy; never null
     * @return the bound executor and the release of its environment; never null
     */
    public BoundCheck bind(Workspace workspace) {
        return switch (placement) {
            case InFreshBoxes fresh -> inBox(fresh.boxes(), workspace);
            case OnHost host -> onHost(host, workspace);
        };
    }

    private BoundCheck onHost(OnHost host, Workspace workspace) {
        if (!(workspace instanceof DirectoryWorkspace directory)) {
            return refused("a check on the host runs over the working copy's directory, and the working"
                    + " copy names none (" + workspace.getClass().getSimpleName() + ")");
        }
        var environment = new HostTaskExecutionEnvironment(
                directory.root(), runners.equipment().time().clock(), host.childEnv(), host.launch());
        environment.materialize(HOST_BRANCH, null);
        return guarded(environment, environment::dispose);
    }

    private BoundCheck inBox(FreshBoxSource source, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attempt)) {
            return refused("a check in a fresh box runs at the attempt commit, and the working copy names" + " none ("
                    + workspace.getClass().getSimpleName() + ")");
        }
        String commit = attempt.attemptCommitSha();
        CheckEnvironmentSource.Acquired box;
        try {
            box = source.freshBox(commit, runners.describe().egress());
        } catch (RuntimeException e) {
            // The source disposed what it built; the failure is this request's answer, reported by the
            // check round's outcome — one failure, one log, at the engine's decision.
            return refused("its fresh box at " + commit + " could not be materialized: "
                    + UntrustedText.subprocess(String.valueOf(e.getMessage())).excerpt(512));
        }
        return guarded(box.environment(), box::close);
    }

    private BoundCheck guarded(TaskExecutionEnvironment environment, Runnable release) {
        // The environment's guard is probed around every request by its one owner (design D13): a
        // dead guard is a cannot-verify, whatever the agent answered.
        return new BoundCheck(
                GuardedExecutor.over(
                        ProgramFailures.Subject.AGENT, runners.name(), environment, runners.runner(environment)),
                null,
                release);
    }

    private BoundCheck refused(String cause) {
        return BoundCheck.unboxed(new Refused(
                runners.describe(),
                ProgramFailures.infrastructure(
                        ProgramFailures.Subject.AGENT, runners.name(), cause, UntrustedText.factory(""))));
    }

    /** Where a check request's environment comes from. */
    private sealed interface Placement permits InFreshBoxes, OnHost {}

    /** A box per request from the run's fresh boxes. */
    private record InFreshBoxes(FreshBoxSource boxes) implements Placement {}

    /** A host environment over the check's working copy. */
    private record OnHost(ChildEnvAllowlist childEnv, ProcessLaunch launch) implements Placement {}

    /** An agent check bound in no environment: every operation answers the one refusal. */
    private record Refused(Describe describe, ExecutorResult refusal) implements Executor {

        @Override
        public ExecutorResult start(ExecutorRequest request) {
            return refusal;
        }

        @Override
        public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
            return refusal;
        }
    }
}
