package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seat of the verify chain (design D2, D3): runs a stage attempt's ordered
 * {@code verify} list in manifest order, one check at a time, stopping at the first
 * non-{@link Verdict.Pass} check so later checks never run (FR2 — cheap deterministic
 * checks are ordered first, so a fast failure short-circuits the expensive ones). Each
 * verdict is dispatched by an exhaustive switch over the sealed {@link VerifyCheck}
 * (design D3): a new variant fails to compile. A thin chain driver: the external poll
 * loop and judge majority loop live in the {@link ExternalPolling} / {@link JudgeVoting}
 * collaborators it delegates to.
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent verifications safely (NFR-R1).
 *
 * <p>Implements FR2, FR3, FR4, FR7, NFR-R3, NFR-O1 of add-stage-engine.
 */
final class VerifyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(VerifyOrchestrator.class);

    private final BuiltinCheckRunner builtinRunner;
    private final CommandCheckRunner commandRunner;
    private final ExternalPolling externalPolling;
    private final JudgeVoting judgeVoting;
    private final Clock clock;
    private final EngineEventListener listener;

    /**
     * Wires the collaborators the verify chain drives: the built-in and command runners,
     * the {@link ExternalPolling} poll loop, the {@link JudgeVoting} majority loop, the
     * injected {@link Clock} it times against, and the {@link EngineEventListener} it
     * emits to. All immutable (NFR-R1).
     *
     * @param builtinRunner the port that runs built-in declarative checks; never null
     * @param commandRunner the port that runs command checks; never null
     * @param externalPolling the poll loop external checks delegate to; never null
     * @param judgeVoting the majority-vote loop judge checks delegate to; never null
     * @param clock the injected time source timing each check; never null
     * @param listener the observer the per-check events are emitted to; never null
     */
    VerifyOrchestrator(
            BuiltinCheckRunner builtinRunner,
            CommandCheckRunner commandRunner,
            ExternalPolling externalPolling,
            JudgeVoting judgeVoting,
            Clock clock,
            EngineEventListener listener) {
        this.builtinRunner = builtinRunner;
        this.commandRunner = commandRunner;
        this.externalPolling = externalPolling;
        this.judgeVoting = judgeVoting;
        this.clock = clock;
        this.listener = listener;
    }

    /**
     * Runs {@code checks} in strict manifest order against {@code workspace}, returning
     * the {@link VerificationResult} of the checks that actually ran. Each check is derived
     * to a {@link CheckRef}, bracketed by a {@link EngineEvent.CheckStarted}/{@link
     * EngineEvent.CheckFinished} pair, timed against the {@link Clock}, and recorded as a
     * {@link CheckResult}; per FR2 the loop breaks at the first non-{@link Verdict.Pass}
     * verdict. An empty list verifies vacuously; accumulation is local (NFR-R1).
     *
     * <p>Implements FR2 of add-stage-engine.
     *
     * @param checks the stage's ordered verify list; may be empty
     * @param context the task context threaded through to judge checks (task 4.4)
     * @param workspace the opaque working copy the checks operate on
     * @param key the {@code (taskId, stage, attempt)} key the per-check events carry
     * @return the results of the checks that ran plus the judge token accounting
     */
    VerificationResult verify(List<VerifyCheck> checks, TaskContext context, Workspace workspace, AttemptKey key) {
        var results = new ArrayList<CheckResult>();
        var judgePerVote = new ArrayList<TokenUsage>();
        for (int i = 0; i < checks.size(); i++) {
            var check = checks.get(i);
            var ref = CheckRef.of(i, check);
            Events.emit(listener, new EngineEvent.CheckStarted(key, ref));
            var start = clock.now();
            var verdict = runCheck(check, ref, context, workspace, judgePerVote);
            var duration = Duration.between(start, clock.now());
            var result = new CheckResult(ref, verdict, duration);
            results.add(result);
            Events.emit(listener, new EngineEvent.CheckFinished(key, result));
            if (!(verdict instanceof Verdict.Pass)) {
                break;
            }
        }
        return new VerificationResult(List.copyOf(results), new JudgeUsage(List.copyOf(judgePerVote)));
    }

    /**
     * Dispatches one check by an exhaustive switch over the sealed {@link VerifyCheck}
     * (design D3): {@link VerifyCheck.Builtin}/{@link VerifyCheck.Command} to their
     * runners, {@link VerifyCheck.External} to the {@link ExternalPolling} poll loop, and
     * {@link VerifyCheck.Judge} to the {@link JudgeVoting} majority loop — whose per-vote
     * token usage is folded into {@code judgePerVote} for the run's {@link JudgeUsage}
     * (NFR-C1). The whole dispatch is wrapped so any {@link RuntimeException} a check
     * adapter throws is caught, logged at ERROR at the point of capture, and turned into a
     * {@link Verdict.CannotVerify} carrying the full stack trace in its details (FR4,
     * NFR-O1) — a check that could not run is an infrastructure failure, not a quality
     * one. No {@code default} arm, so a new variant fails to compile.
     *
     * @param check the check to run
     * @param ref the check's identity, named in the ERROR log on an adapter throw
     * @param context the task context threaded through to judge checks (FR7)
     * @param workspace the opaque working copy the check operates on
     * @param judgePerVote the run's accumulator of per-vote judge token usage
     * @return the verdict the runner reached, or a {@code CannotVerify} on an adapter throw
     */
    private Verdict runCheck(
            VerifyCheck check, CheckRef ref, TaskContext context, Workspace workspace, List<TokenUsage> judgePerVote) {
        try {
            return switch (check) {
                case VerifyCheck.Builtin b -> builtinRunner.run(b, workspace);
                case VerifyCheck.Command c -> commandRunner.run(c, workspace);
                case VerifyCheck.External e -> externalPolling.poll(e, workspace);
                case VerifyCheck.Judge j -> {
                    var result = judgeVoting.vote(j, context, workspace);
                    judgePerVote.addAll(result.perVote());
                    yield result.verdict();
                }
            };
        } catch (RuntimeException ex) {
            log.error("check adapter threw for {}", ref, ex);
            return new Verdict.CannotVerify("check adapter threw", StackTraces.render(ex));
        }
    }
}
