package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/**
 * A {@link TrackerAdapterContext} of fixed values — what a spec hands a tracker adapter factory in
 * place of the host's own context (design D21 of supervise-daemon-loops-and-embed-dashboard). The
 * time equipment defaults to a fresh virtual one, so a spec that builds an adapter through this
 * context runs it on virtual time unless it says otherwise.
 *
 * <p>Test fixture; never shipped. Implements FR23 of supervise-daemon-loops-and-embed-dashboard.
 */
final class FixedTrackerAdapterContext implements TrackerAdapterContext {

    final SecretsProvider secrets
    final TrackerConfig config
    final String instanceId
    final ClaimEpochSource epochs
    final TimeEquipment timeEquipment

    FixedTrackerAdapterContext(
    SecretsProvider secrets,
    TrackerConfig config,
    String instanceId,
    ClaimEpochSource epochs = ClaimEpochSource.NONE,
    TimeEquipment timeEquipment = VirtualTimeEquipment.create()) {
        this.secrets = secrets
        this.config = config
        this.instanceId = instanceId
        this.epochs = epochs
        this.timeEquipment = timeEquipment
    }

    /** A context resolving no credential, holding no tenure, on fresh virtual time. */
    static FixedTrackerAdapterContext of(TrackerConfig config, String instanceId) {
        new FixedTrackerAdapterContext(MapSecretsProvider.NONE, config, instanceId)
    }

    @Override
    SecretsProvider secrets() {
        secrets
    }

    @Override
    TrackerConfig config() {
        config
    }

    @Override
    String instanceId() {
        instanceId
    }

    @Override
    ClaimEpochSource epochs() {
        epochs
    }

    @Override
    TimeEquipment timeEquipment() {
        timeEquipment
    }
}
