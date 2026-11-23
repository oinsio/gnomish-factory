package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramFailures;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.agent.program.WorkingCopyCheck;
import com.github.oinsio.gnomish.adapter.agent.program.WorkingCopyRefusal;
import com.github.oinsio.gnomish.adapter.agent.program.WorkingCopyView;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one runner of an agent CLI round, in every role (FR1, FR2, NFR-S2 of extract-agent-round-runner;
 * design D1): a {@code transform}, a {@code verdict} and a {@code decision} request run through one code
 * path — the dialect's argv, the launch through the environment's process seam, the capped stdout
 * drain, the kept stderr file, the three bounds, the answer through the shared capped read ({@link
 * AgentRound}, {@link RoundJudgement}) — and only the dialect's argv and answer extraction differ.
 * The answer is obtained as the dialect declares for the role ({@link AnswerForm}): a schema-enforced
 * reply the runner writes to the round's answer path, or a file the agent writes there; the runner
 * validates both through the one answer read (FR19, design D15). The
 * runner depends on nothing else from the vendor: whatever names the vendor is the {@link
 * AgentDialect}'s.
 *
 * <p>A {@code verdict} or {@code decision} round is checked to leave the working copy as it found it,
 * by the same check a {@code program} run takes ({@link WorkingCopyCheck}): git's view is taken before
 * the round — a view that cannot be taken refuses the round before it starts — and again when it
 * ended, and a difference refuses its answer, {@code failed/infrastructure} naming the paths. The
 * compiled read-only policy is the dialect's; this check does not trust it (NFR-S2).
 *
 * <p>The round's progress listener is held behind {@link CompositeAgentProgressListener}, so a
 * listener that throws costs a log line, never the reader that calls it while the CLI runs.
 *
 * <p>An agent round never returns {@code pending} (proposal NG5), so {@link #poll} refuses whatever
 * it is asked. The egress guard's probes and the environment's denials are the round wrapper's, read
 * once around the executor's {@code start} as for a {@code program} executor; the runner reads
 * neither, so the guard's per-round cursor advances once.
 *
 * <p>Implements FR1, FR2, FR8, FR19, NFR-R1, NFR-S2 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
public final class AgentRoundRunner<E> implements Executor {

    private final String name;
    private final AgentDialect<E> dialect;
    private final TaskExecutionEnvironment environment;
    private final ProgramRoundEquipment equipment;
    private final AgentRound<E> round;

    /**
     * @param name the executor's name; never blank
     * @param dialect the vendor's dialect; never null
     * @param environment where the rounds run; materialized; never null
     * @param equipment the run's bounds, time, credential grant, stderr parent and round cell; never null
     * @param listener the rounds' progress listener; never null
     * @param caps the rounds' stdout caps; never null
     * @param roundAnswerFile the open round's answer path, where a schema-enforced answer is written
     *     and every answer read; null for a request served outside a round source, whose answer path
     *     is a fresh one in the environment's scratch area
     */
    public AgentRoundRunner(
            String name,
            AgentDialect<E> dialect,
            TaskExecutionEnvironment environment,
            ProgramRoundEquipment equipment,
            AgentProgressListener listener,
            StdoutCaps caps,
            @Nullable String roundAnswerFile) {
        this.name = name;
        this.dialect = dialect;
        this.environment = environment;
        this.equipment = equipment;
        this.round = new AgentRound<>(
                name,
                dialect,
                environment,
                equipment,
                new CompositeAgentProgressListener(List.of(listener)),
                caps,
                roundAnswerFile);
    }

    @Override
    public Describe describe() {
        return dialect.describe();
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        if (!WorkingCopyCheck.applies(request.role())) {
            return round.run(request);
        }
        WorkingCopyCheck check = new WorkingCopyCheck(
                environment, equipment.bounds().startup(), equipment.time().clock(), equipment.registered());
        return switch (check.view()) {
            case WorkingCopyView.Unreadable unreadable ->
                new WorkingCopyRefusal(ProgramFailures.Subject.AGENT, name, request).before(unreadable);
            case WorkingCopyView.Taken before ->
                check.judge(ProgramFailures.Subject.AGENT, name, request, before, round.run(request));
        };
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return ProgramFailures.infrastructure(
                ProgramFailures.Subject.AGENT,
                name,
                "an agent round cannot be polled: it never returns pending",
                UntrustedText.factory(""));
    }
}
