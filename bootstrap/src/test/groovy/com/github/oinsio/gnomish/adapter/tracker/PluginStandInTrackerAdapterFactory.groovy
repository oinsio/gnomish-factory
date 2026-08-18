package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/**
 * The stand-in for a third-party plugin jar's tracker provider: a factory no core source file
 * names, discovered only because a {@code META-INF/services} entry points at it (FR1 of
 * add-plugin-architecture). {@link TrackerAdapterDiscoverySpec} stages that entry on a class loader
 * of its own, so this class is NOT declared in any service file compiled into the build — which is
 * exactly what keeps the production registry to its two real providers.
 */
class PluginStandInTrackerAdapterFactory implements TrackerAdapterFactory {

    static final String TYPE = 'plugin-standin'

    @Override
    String type() {
        TYPE
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        throw new UnsupportedOperationException('the plugin stand-in builds no tracker')
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        new TaskRef(rawRef)
    }
}
