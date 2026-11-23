package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;

/**
 * The bundle of collaborators the engine drives instead of touching the outside
 * world — the {@code ports} argument of {@link Engine#run} (design D1). Grouping the
 * ports into one value keeps the entry-point signature small and lets a caller wire a
 * factory once and reuse it across runs; the engine reads each field but never stores
 * it in shared state, so the same {@code EnginePorts} can back concurrent runs (NFR-R1).
 *
 * <p>One execution port does the real work — {@link StageExecutor} runs a stage round and every
 * check's rounds (design D18 of migrate-verify-to-executor-contract: a check round names its check
 * as the request's subject); the three former check ports are gone (task 5.2 of that change), and so
 * is the judge's voter (design D2 of extract-agent-round-runner, M3): a judge check is one {@code
 * verdict} request on the same port, so every check reaches its executor only through it.
 * The remaining four are cross-cutting seams: {@link EngineEventListener} observes the
 * event stream (D7), {@link AttemptPersistence} durably records each round (D7), the
 * environment ports — the {@link TimeEquipment}, the current instant and waiting as one value
 * (design D20 of supervise-daemon-loops-and-embed-dashboard) — make the poll loop's timing
 * deterministic (D8), {@link AttemptDelivery} verifies the polled check's push
 * precondition before its first round (FR21 of add-sandbox-core), and {@link RoundIdentity}
 * yields the token of each round the engine records (FR14, FR21 of define-executor-contract).
 *
 * <p>An inert record: it holds the collaborators and enforces no rule beyond the
 * non-null-by-default contract of the {@code @NullMarked} package. Compared by content.
 *
 * <p>Implements FR1 of add-stage-engine; FR21 of add-sandbox-core; FR17 of
 * supervise-daemon-loops-and-embed-dashboard (the instant-source environment port); FR22 of
 * supervise-daemon-loops-and-embed-dashboard (the time equipment as the environment ports); M3 of
 * extract-agent-round-runner (one executor port).
 *
 * @param executor the port that runs one round of a stage's work; never null
 * @param listener the observer the engine emits its event stream to; never null
 * @param persistence the port that durably records each executed round; never null
 * @param time the environment ports: the instant source for timestamps and poll deadlines and the
 *     sleep seam the poll loop waits on, as one value; never null
 * @param attemptDelivery the external-check push-precondition seam; never null
 * @param describedExecutors what the describe preflight obtained for this run — the run-held value
 *     the engine pre-flight compares with the task's executor version pin and the round writers pin
 *     from (FR13 of define-executor-contract, design D11); {@link DescribedExecutors#none()} only for an
 *     engine assembled with no preflight (the in-place constructor below); never null
 * @param roundIdentity where the engine takes each recorded round's token: the run's current-round
 *     cell in a branch mode, {@link RoundIdentity#inPlace()} in the in-place run; no constructor
 *     defaults it (D13 of define-executor-contract); never null
 */
public record EnginePorts(
        StageExecutor executor,
        EngineEventListener listener,
        AttemptPersistence persistence,
        TimeEquipment time,
        AttemptDelivery attemptDelivery,
        DescribedExecutors describedExecutors,
        RoundIdentity roundIdentity) {

    /**
     * The dominant construction: assemblies and specs whose checks have no
     * push precondition to verify (the in-place shape) default {@code
     * attemptDelivery} to {@link AttemptDelivery#assumedDelivered()}; sandboxed git assemblies
     * use the canonical constructor with a real push-verifying implementation (FR21 of
     * add-sandbox-core). Such a run describes no executor: {@link DescribedExecutors#none()}. The
     * round identity is never defaulted: each caller names its own.
     */
    public EnginePorts(
            StageExecutor executor,
            EngineEventListener listener,
            AttemptPersistence persistence,
            TimeEquipment time,
            RoundIdentity roundIdentity) {
        this(
                executor,
                listener,
                persistence,
                time,
                AttemptDelivery.assumedDelivered(),
                DescribedExecutors.none(),
                roundIdentity);
    }
}
