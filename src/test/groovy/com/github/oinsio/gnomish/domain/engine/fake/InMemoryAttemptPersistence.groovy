package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import groovy.transform.Canonical

/**
 * An in-memory {@link AttemptPersistence}: records each {@code persist} call as an
 * {@link Entry} in the public {@link #entries} list, in order. Set {@link #failOnCall}
 * (1-based; 0 = never) to make the Nth persist throw a {@link RuntimeException},
 * driving the Aborted-on-broken-durability tests.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class InMemoryAttemptPersistence implements AttemptPersistence {

    /** One recorded persist call. */
    @Canonical
    static class Entry {
        String taskId
        TaskState state
        ToolTrace trace
    }

    /** Every persisted call, in order. */
    final List<Entry> entries = []

    /** 1-based call number that throws; 0 (default) never throws. */
    int failOnCall = 0

    @Override
    void persist(String taskId, TaskState state, ToolTrace trace) {
        entries << new Entry(taskId, state, trace)
        if (failOnCall > 0 && entries.size() == failOnCall) {
            throw new RuntimeException('persist failed on call ' + failOnCall + ' for task ' + taskId)
        }
    }
}
