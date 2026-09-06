package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * Maps a terminal {@link TaskOutcome} to the {@link TaskLifecycleEvent} its commit is stamped
 * with. Shared by every {@code TaskRepository} realization in this package ({@link
 * GitTaskRepository}, {@link GitObjectsTaskRepository}, {@link PushBestEffortTaskRepository}) so
 * the mapping is defined once rather than three times.
 */
final class TaskOutcomeLifecycleEvent {

    private TaskOutcomeLifecycleEvent() {}

    static TaskLifecycleEvent of(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> TaskLifecycleEvent.COMPLETED;
            case TaskOutcome.Paused ignored -> TaskLifecycleEvent.PAUSED;
            case TaskOutcome.Escalated ignored -> TaskLifecycleEvent.ESCALATED;
            case TaskOutcome.Aborted ignored -> TaskLifecycleEvent.ABORTED;
        };
    }
}
