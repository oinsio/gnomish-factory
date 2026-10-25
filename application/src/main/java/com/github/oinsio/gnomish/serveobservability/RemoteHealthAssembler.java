package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.serve.DaemonConditions;
import com.github.oinsio.gnomish.app.serve.RemoteOutageHealth;
import java.util.Map;

/**
 * Builds the snapshot's {@code remote} section (NFR-O3, UX6 of add-base-ref-resolution) from the
 * remote condition of the daemon's {@link DaemonConditions}, one map entry keyed by its {@link
 * RemoteOutageHealth#target()} — the daemon has one remote (task 7.3 of add-base-ref-resolution); mirrors
 * {@link TrackerHealthAssembler}'s role.
 *
 * <p>Stateless: holds no fields, only assembles a fresh map from the conditions handed to it on
 * each call.
 *
 * <p>Implements NFR-O3, UX6 of add-base-ref-resolution.
 */
public final class RemoteHealthAssembler {

    private RemoteHealthAssembler() {}

    /**
     * Assembles the {@code remote} section from the remote condition's current state.
     *
     * @param conditions the daemon's conditions, whose remote the section describes; never null
     * @return the assembled section, one entry keyed by the remote's target; never null
     */
    public static Map<String, RemoteHealth> assemble(DaemonConditions conditions) {
        RemoteOutageHealth health = conditions.remote().health();
        return Map.of(
                health.target(),
                new RemoteHealth(
                        health.target(),
                        health.open(),
                        health.openSince(),
                        health.lastError(),
                        health.nextProbeAt(),
                        health.consecutiveFailures(),
                        health.lastSuccessAt()));
    }
}
