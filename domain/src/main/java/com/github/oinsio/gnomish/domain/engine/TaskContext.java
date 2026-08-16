package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The identity and human description of a task, plus the chronological human
 * decisions carried alongside it. The engine treats {@code taskId} as an opaque
 * key — an unstructured identifier it never parses — and passes {@code title},
 * {@code body} and {@code decisions} through to executor and judge requests
 * unmodified (design D6): human decisions are context, never commands.
 *
 * <p>Inert, immutable value data: {@code taskId} is required non-blank because
 * it is the task's key, while {@code title} and {@code body} are the human
 * description — required non-null but allowed to be empty text, since a task may
 * legitimately have an empty title or body. The {@code decisions} list is
 * defensively copied and unmodifiable and may be empty (a task need not carry
 * any decisions yet); its order is the chronological order supplied by the
 * caller and is preserved faithfully.
 *
 * <p>Implements FR7 of add-stage-engine.
 *
 * @param taskId the opaque task identifier; never blank, never parsed
 * @param title the task's human title; never null, may be empty
 * @param body the task's human description; never null, may be empty
 * @param decisions the chronological human decisions; defensively copied,
 *     unmodifiable, possibly empty
 */
public record TaskContext(String taskId, String title, String body, List<Decision> decisions) {

    public TaskContext {
        taskId = requireNonBlank(taskId, "taskId");
        decisions = List.copyOf(decisions);
    }

    /**
     * Fails fast on a blank {@code taskId}: the opaque key must identify the task
     * (FR7). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("TaskContext." + component + " must not be blank");
        }
        return value;
    }
}
