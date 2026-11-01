package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;

/**
 * The port through which the engine durably records the outcome of one executed
 * round — the new {@link TaskState} and the raw {@link ToolTrace} — without knowing
 * where they land (design D7): a commit on the task branch, a database, a file. The
 * engine calls {@link #persist} <em>synchronously</em> after every executed round,
 * before it emits the {@code AttemptFinished} event and before it starts any next
 * attempt, so a resumed run always sees the last durable state (FR11).
 *
 * <p>The durability guarantee is load-bearing: an implementation that cannot make the
 * round durable signals it by <em>throwing</em>. The engine turns a thrown persist
 * into an {@code Aborted} outcome — the only outcome for a broken durability
 * guarantee, since continuing past an unpersisted round would risk losing or
 * double-executing work on resume.
 *
 * <p>Implements FR11 of add-stage-engine.
 */
public interface AttemptPersistence {

    /**
     * Durably records the round's new {@code state} and raw {@code trace} under
     * {@code taskId}, synchronously and before the engine proceeds. An implementation
     * that cannot persist must throw: the engine treats a thrown persist as a broken
     * durability guarantee and aborts the run rather than continuing on unpersisted
     * state.
     *
     * <p>Implements FR11 of add-stage-engine.
     *
     * @param taskId the task whose round is being persisted; never blank
     * @param state the task state produced by the round, its last attempt the round itself (every
     *     round writer appends the round it records, see {@link #roundOf}); never null
     * @param trace the round's raw tool trace; never null
     */
    void persist(String taskId, TaskState state, ToolTrace trace);

    /**
     * The round a {@link #persist} call records: the last attempt of the state it was handed, since
     * every round writer of {@link TaskState} appends the round it records — a pass that advances the
     * position keeps it there too (FR14 of define-executor-contract). The one spelling of that read,
     * so an implementation renders its commit subject from the record rather than from the trace's
     * key or the position.
     *
     * @param state the state a {@code persist} call was handed; never null
     * @return the recorded round; never null
     * @throws java.util.NoSuchElementException if the state records no round — not a round write
     */
    static AttemptRecord roundOf(TaskState state) {
        return state.attempts().getLast();
    }
}
