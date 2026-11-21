package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramEnd;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.agent.program.ProgressLines;
import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.sandbox.CredentialGrantStanding;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.ProgramCredentialGrant;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One agent CLI round, launched and watched to its end (FR1, FR8, NFR-R1 of
 * extract-agent-round-runner): the dialect's argv and stdin, with the credentials the dialect's
 * describe names granted through the run's allowlist and a fresh factory-named answer path in {@code
 * GNOMISH_ANSWER_FILE}, spawned only through the environment's process seam; stderr to a file in the
 * round scratch directory ({@link RoundScratchDirectory}), read capped after the exit and never through
 * a pipe of its own; stdout read live from the launch by the one line reader ({@link ProgressLines})
 * under the line cap, each line parsed by the dialect ({@link DialectLines}); the wait under the
 * round's three bounds, resolved once per run by {@link RoundBounds} — the factory's startup-read and
 * silence bounds, the overall bound the request's {@code roundTimeout} declares or the factory's — on
 * the run's time equipment ({@code ProgressWait}), an expiry killing the process tree. The dialect
 * renders the request with those same bounds as its time hints, so the bound the CLI is told and the
 * bound it is killed at are one value. How the process ended is judged by {@link
 * RoundJudgement}.
 *
 * <p>Every outcome is a result, never a throw: a refused start, an expired bound, an interrupt and a
 * stdout that did not end within the handle's post-exit bound are each {@code failed/infrastructure}
 * naming what went wrong, with the stderr tail.
 *
 * <p>A request carrying a continuation whose resume the CLI refused (the dialect's judgement, {@link
 * AgentDialect#resumeRefused}) is started once more as a fresh request with the reply in its feedback,
 * after one WARN; the result is the fresh round's (FR9, NFR-R2, design D4).
 *
 * <p>Implements FR1, FR7, FR8, FR9, NFR-R1, NFR-R2 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
final class AgentRound<E> {

    private final String name;
    private final AgentDialect<E> dialect;
    private final TaskExecutionEnvironment environment;
    private final ProgramRoundEquipment equipment;
    private final AgentProgressListener listener;
    private final StdoutCaps caps;

    /**
     * @param name the executor's name; never blank
     * @param dialect the vendor's dialect; never null
     * @param environment where the round runs; materialized; never null
     * @param equipment the run's bounds, time, credential grant and stderr parent; never null
     * @param listener the round's progress listener, which must not throw; never null
     * @param caps the round's stdout caps; never null
     */
    AgentRound(
            String name,
            AgentDialect<E> dialect,
            TaskExecutionEnvironment environment,
            ProgramRoundEquipment equipment,
            AgentProgressListener listener,
            StdoutCaps caps) {
        this.name = name;
        this.dialect = dialect;
        this.environment = environment;
        this.equipment = equipment;
        this.listener = listener;
        this.caps = caps;
    }

    /**
     * Runs {@code request} as one round; never throws for what the CLI did.
     *
     * A refused resume runs once more as the fresh request {@link SessionContinuation#fallback} makes,
     * which carries no continuation and so cannot be refused again.
     *
     * @param request the run, in exactly one role; never null
     * @return the round's result; never null
     */
    ExecutorResult run(ExecutorRequest request) {
        return switch (attempt(request)) {
            case RoundAttempt.Ended ended -> ended.result();
            case RoundAttempt.ResumeRefused refused ->
                run(SessionContinuation.fallback(name, request, refused.stderrTail()));
        };
    }

    private RoundAttempt attempt(ExecutorRequest request) {
        RoundBounds bounds = RoundBounds.of(equipment.bounds(), request);
        RenderedArgv rendered = dialect.render(bounds.hinted(request));
        String answerFile = ProgramChannel.freshPath(environment, name, "answer");
        Map<String, String> variables = new LinkedHashMap<>(credentials());
        variables.put(ProgramChannel.ANSWER_FILE_VARIABLE, answerFile);
        try (RoundScratchDirectory scratch =
                RoundScratchDirectory.open(equipment.stderrParent(), RoundScratchDirectory.Writer.AGENT_CLI)) {
            RoundAttempt attempt = launched(
                    new ExecCommand(rendered.argv(), variables, rendered.stdin(), scratch.stderr()),
                    answerFile,
                    scratch,
                    bounds,
                    request);
            if (attempt instanceof RoundAttempt.Ended(ExecutorResult result) && result.status() == Status.COMPLETED) {
                scratch.logTailOnSuccess();
            }
            return attempt;
        }
    }

    /**
     * The credentials the dialect's describe declares, granted through the run allowlist's grant —
     * those that can be granted, that is: an agent CLI authenticates by any one of several
     * credentials, or through its own logged-in {@code HOME}, so a declared name with no value is
     * omitted rather than a failure, and so is a name the allowlist withholds (a declared tracker
     * or check credential) or passes through to every child already (NFR-S1 of
     * extract-agent-round-runner; NFR-S3 of tighten-spec-contract).
     */
    private Map<String, String> credentials() {
        ProgramCredentialGrant grant = equipment.grant();
        List<String> grantable = dialect.describe().credentials().stream()
                .filter(name -> grant.standing(name) == CredentialGrantStanding.GRANTABLE)
                .toList();
        return grant.grant(grantable);
    }

    private RoundAttempt launched(
            ExecCommand command,
            String answerFile,
            RoundScratchDirectory scratch,
            RoundBounds bounds,
            ExecutorRequest request) {
        var judgement = new RoundJudgement<>(name, dialect, environment, scratch, request);
        ExecHandle handle;
        try {
            handle = environment.exec(command);
        } catch (ProcessStartException refused) {
            return new RoundAttempt.Ended(judgement.failure("the environment refused to start the agent CLI: "
                    + UntrustedText.subprocess(String.valueOf(refused.getMessage()))
                            .excerpt(512)));
        }
        var time = equipment.time();
        var lines = new DialectLines<>(dialect, listener, time.clock(), caps);
        ProgramEnd end;
        boolean drained;
        try (ProgressLines reader = ProgressLines.start(handle.output(), time.clock(), caps.lineBytes(), lines)) {
            end = bounds.waitOn(time).await(handle, reader);
            drained = reader.await(handle.postExitBound());
        }
        if (end instanceof ProgramEnd.Exited exited && drained) {
            return judgement.exited(exited, lines, answerFile, time.clock().instant());
        }
        return new RoundAttempt.Ended(
                switch (end) {
                    case ProgramEnd.Exited ignored ->
                        judgement.failure(
                                "its stdout did not end within the post-exit bound of " + handle.postExitBound());
                    case ProgramEnd.BoundExpired expired ->
                        judgement.failure("the " + expired.bound() + " bound of " + expired.value()
                                + " expired and its process tree was killed");
                    case ProgramEnd.Interrupted ignored ->
                        judgement.failure("the round was interrupted and its process tree killed");
                });
    }
}
