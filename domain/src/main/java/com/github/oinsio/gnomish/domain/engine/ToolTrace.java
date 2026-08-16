package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The raw chronological trace of tool {@link ToolCall}s for one attempt, headed
 * by its {@link AttemptKey}. Per design D5 the aggregate metrics live inside
 * {@code AttemptRecord}, while this raw trace is deliberately kept <em>outside</em>
 * {@code TaskState} and correlated back to its attempt by the {@code key} — the
 * same {@code (taskId, stage, attempt)} key that logs and telemetry share (UX2).
 *
 * <p>The {@code calls} list is defensively copied and unmodifiable and may be
 * empty (an attempt need not have invoked any tool); its order is the chronological
 * order supplied by the caller and is preserved faithfully. Inert value data
 * compared by content.
 *
 * <p>Implements FR13 of add-stage-engine.
 *
 * @param key the {@code (taskId, stage, attempt)} correlation header
 * @param calls the chronological tool calls; defensively copied, unmodifiable,
 *     possibly empty
 */
public record ToolTrace(AttemptKey key, List<ToolCall> calls) {

    public ToolTrace {
        calls = List.copyOf(calls);
    }
}
