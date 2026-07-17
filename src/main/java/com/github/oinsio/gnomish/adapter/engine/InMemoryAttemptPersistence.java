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
 * the accumulate-not-overwrite guarantee the port contract asserts.
 *
 * <p>Implements D10, M2, NG3 of add-manual-run.
 */
public final class InMemoryAttemptPersistence implements AttemptPersistence {

    /** One recorded persist call. */
    public record Entry(String taskId, TaskState state, ToolTrace trace) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        entries.add(new Entry(taskId, state, trace));
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
