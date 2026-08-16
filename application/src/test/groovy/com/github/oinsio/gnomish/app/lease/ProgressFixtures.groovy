package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent

/**
 * Shared {@link HeartbeatProgress} seeding for the {@link InstanceHeartbeat} beat specs
 * ({@link InstanceHeartbeatSpec}, {@link BeatFailureTaxonomySpec}), which previously each
 * defined a byte-for-byte identical {@code progressAt} helper to drive the same stage/attempt
 * payload derivation.
 */
class ProgressFixtures {

    private ProgressFixtures() {
    }

    static void progressAt(HeartbeatProgress progress, TaskRef ref, String stage, int attempt) {
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(ref.id(), stage, attempt)))
    }
}
