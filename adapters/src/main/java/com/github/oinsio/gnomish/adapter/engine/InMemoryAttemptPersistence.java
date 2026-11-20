package com.github.oinsio.gnomish.adapter.engine;

import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The production {@link AttemptPersistence}: durably records every persisted round
 * for the lifetime of the process. Per NG3 of add-manual-run, persistence stays
 * in-memory for this change — no git or database backing yet — so "durable" here
 * means "retained in the running process," not "survives a restart."
 *
 * <p>Each {@link #persist} call appends an {@link Entry} to an internal
 * thread-safe list, in call order; nothing is ever clobbered or dropped, matching
 * the accumulate-not-overwrite guarantee the port contract asserts. Each {@link
 * #persistPending} call is kept the same way, in its own list ({@link #pendingWrites}): the
 * in-place run has no branch and no resume, so its open round's pending entries live in memory
 * only (design D21 of migrate-verify-to-executor-contract, "An in-place run keeps the key in
 * memory").
 *
 * <p>Implements D10, M2, NG3 of add-manual-run; FR2 of migrate-verify-to-executor-contract.
 */
public final class InMemoryAttemptPersistence implements AttemptPersistence {

    /** One recorded persist call. */
    public record Entry(String taskId, TaskState state, ToolTrace trace) {}

    /** One recorded pending write: the task and the state carrying the open round's entries. */
    public record PendingWrite(String taskId, TaskState state) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<PendingWrite> pendingWrites = new CopyOnWriteArrayList<>();

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        entries.add(new Entry(taskId, state, trace));
    }

    @Override
    public void persistPending(String taskId, TaskState state) {
        pendingWrites.add(new PendingWrite(taskId, state));
    }

    /**
     * Every pending write, in order; an unmodifiable view.
     *
     * @return the retained pending writes, in call order
     */
    public List<PendingWrite> pendingWrites() {
        return Collections.unmodifiableList(pendingWrites);
    }

    /**
     * Every persisted call, in order; an unmodifiable snapshot view.
     *
     * @return the retained entries, in persist order
     */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }
}
