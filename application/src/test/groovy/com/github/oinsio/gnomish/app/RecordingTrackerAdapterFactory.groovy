package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/** Records the {@code instanceId} it was called with (design D8: minted but never written). */
class RecordingTrackerAdapterFactory implements TrackerAdapterFactory {

    private final Tracker tracker
    String capturedInstanceId

    RecordingTrackerAdapterFactory(Tracker tracker) {
        this.tracker = tracker
    }

    @Override
    String type() {
        'github'
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        capturedInstanceId = instanceId
        tracker
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture')
    }
}
