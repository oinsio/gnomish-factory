/**
 * Application-layer ports owned by the runner rather than the engine.
 *
 * <p>Defines {@code TaskRepository}: task-scoped lifecycle writes — create the
 * task, append a resume {@code Decision}, record the final {@code TaskOutcome}
 * — kept as a separate seam from the engine's own
 * {@code domain.engine.port.AttemptPersistence} (design D1 of add-git-workflow):
 * the engine stays pure while lifecycle events (start, decision, outcome) are a
 * runner concern.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.port;

import org.jspecify.annotations.NullMarked;
