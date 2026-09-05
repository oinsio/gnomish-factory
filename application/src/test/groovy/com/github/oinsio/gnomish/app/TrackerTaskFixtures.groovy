package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * Shared Spock fixture for the {@code TrackerTask} that {@code tracker.fetchTask} stubs return
 * across the take-pipeline specs, varying only the lifecycle state under test. Extracted because
 * an identical private {@code taskWith(TrackerTaskState)} helper was hand-duplicated across
 * {@code TakeEscalationExitSpec}, {@code TakeFinishReportSpec}, {@code TakePauseExitSpec},
 * {@code TakeParkRetrySpec}, {@code take.ClaimGuardSpec}, {@code take.SelfFencingBoundarySpec} and
 * {@code take.RevocationCheckingAttemptPersistenceSpec}.
 */
class TrackerTaskFixtures {

    static TrackerTask taskWith(TaskRef ref, TrackerTaskState state) {
        new TrackerTask(ref, new TaskSnapshot(ref.id(), 'title', 'body'), state, AbortFacts.none(), false)
    }
}
