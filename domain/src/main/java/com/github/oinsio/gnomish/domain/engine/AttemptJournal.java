package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-round persistence-and-events plumbing the {@link StageAttemptLoop} delegates to,
 * so the loop stays focused on executing rounds and routing their outcomes. Holds the run's
 * {@link EngineEventListener} and {@link AttemptPersistence} and owns the ordering invariant
 * every round must obey: a round is persisted synchronously BEFORE its
 * {@link EngineEvent.AttemptFinished} event and before any next round starts (FR11, the
 * choreography diagram's ordering invariant).
 *
 * <p>Package-private and reentrant: it holds only its two immutable injected collaborators
 * (plus a static logger) and no mutable state, so one instance serves concurrent runs safely
 * (NFR-R1). Event delivery swallows-and-logs any listener exception so a broken listener never
 * breaks a run (design D7); a thrown persist logs at ERROR at the point of capture and is
 * surfaced to the loop as a {@link TaskOutcome.Aborted} (NFR-O1).
 *
 * <p>Implements FR11, FR12, NFR-O1 of add-stage-engine.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.logtext.OperatorEvent}: this class's
 * operator line repeats catalog code {@code GF110} as a literal head, because {@code :domain}
 * must not take a {@code :logtext} edge to reach the catalog (ADR 0004, accepted deviation 1).
 * The literal and the constant are pinned equal by {@code DomainOperatorEventHeadSpec}; there is
 * no resolvable link either way, which is why the pair is listed in
 * {@code .claude/rules/manual-sync-pairs.md}.
 *
 * @param listener the listener the round's events are delivered to; never null
 * @param persistence the port each round's state is persisted through; never null
 */
record AttemptJournal(EngineEventListener listener, AttemptPersistence persistence) {

    private static final Logger log = LoggerFactory.getLogger(AttemptJournal.class);

    /**
     * Emits {@link EngineEvent.AttemptStarted} for the round about to run under {@code key}
     * (FR12), before the executor is invoked.
     *
     * @param key the round's correlation key; never null
     */
    void started(AttemptKey key) {
        Events.emit(listener, new EngineEvent.AttemptStarted(key));
    }

    /**
     * Commits an executed round: persists {@code newState} and the round's {@code trace}
     * synchronously (FR11), then — on success — emits {@link EngineEvent.AttemptFinished} and
     * returns {@code null} so the loop routes the round. On a thrown persist it logs at ERROR at
     * the point of capture and returns a {@link TaskOutcome.Aborted} with {@code key} and the
     * failure's preserved stack trace (NFR-O1) WITHOUT emitting the finish event — the loop ends
     * the run on that non-null return.
     *
     * <p>Implements FR11, NFR-O1 of add-stage-engine.
     *
     * @param taskId the task whose round is being committed; never null
     * @param newState the state recorded for the round; never null
     * @param key the round's correlation key; never null
     * @param trace the executor's raw tool trace persisted with the round; never null
     * @return {@code null} on success; a {@link TaskOutcome.Aborted} when persist threw
     */
    @Nullable
    TaskOutcome commit(String taskId, TaskState newState, AttemptKey key, ToolTrace trace) {
        try {
            persistence.persist(taskId, newState, trace);
        } catch (RuntimeException ex) {
            log.error("[GF110] persist failed for {}", key, ex);
            return new TaskOutcome.Aborted(newState, key, StackTraces.render(ex));
        }
        Events.emit(listener, new EngineEvent.AttemptFinished(key, newState, trace));
        return null;
    }
}
