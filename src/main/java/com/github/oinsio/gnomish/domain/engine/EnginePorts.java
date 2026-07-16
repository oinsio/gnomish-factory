package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;

/**
 * The bundle of nine collaborators the engine drives instead of touching the outside
 * world — the {@code ports} argument of {@link Engine#run} (design D1). Grouping the
 * ports into one value keeps the entry-point signature small and lets a caller wire a
 * factory once and reuse it across runs; the engine reads each field but never stores
 * it in shared state, so the same {@code EnginePorts} can back concurrent runs (NFR-R1).
 *
 * <p>Five execution/verification ports do the real work — {@link StageExecutor} runs a
 * round, and the four check ports ({@link BuiltinCheckRunner}, {@link CommandCheckRunner},
 * {@link ExternalCheckClient}, {@link JudgeVoter}) serve the verify chain (design D2).
 * The remaining four are cross-cutting seams: {@link EngineEventListener} observes the
 * event stream (D7), {@link AttemptPersistence} durably records each round (D7), and the
 * injected {@link Clock}/{@link Sleeper} make the poll loop's timing deterministic (D8).
 *
 * <p>An inert record: it holds the collaborators and enforces no rule beyond the
 * non-null-by-default contract of the {@code @NullMarked} package. Compared by content.
 *
 * <p>Implements FR1 of add-stage-engine.
 *
 * @param executor the port that runs one round of a stage's work; never null
 * @param builtinRunner the port that runs built-in declarative checks; never null
 * @param commandRunner the port that runs command checks; never null
 * @param externalClient the port that polls external checks; never null
 * @param judgeVoter the port that casts one judge vote; never null
 * @param listener the observer the engine emits its event stream to; never null
 * @param persistence the port that durably records each executed round; never null
 * @param clock the injected time source for timestamps and poll deadlines; never null
 * @param sleeper the injected sleep seam the poll loop waits on; never null
 */
public record EnginePorts(
        StageExecutor executor,
        BuiltinCheckRunner builtinRunner,
        CommandCheckRunner commandRunner,
        ExternalCheckClient externalClient,
        JudgeVoter judgeVoter,
        EngineEventListener listener,
        AttemptPersistence persistence,
        Clock clock,
        Sleeper sleeper) {}
