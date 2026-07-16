package com.github.oinsio.gnomish.domain.engine.port;

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
     * @param state the task state produced by the round; never null
     * @param trace the round's raw tool trace; never null
     */
    void persist(String taskId, TaskState state, ToolTrace trace);
}
