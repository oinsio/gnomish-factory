package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.serve.RemoteOutageGate;
import com.github.oinsio.gnomish.app.serve.RemoteOutageHealth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the snapshot's {@code remote} section (NFR-O3, UX6 of add-base-ref-resolution) from the
 * daemon's {@link RemoteOutageGate}s, one map entry per gate's own {@link
 * RemoteOutageHealth#target()}
 * — today always exactly one, since task 7.3 scoped the daemon to one gate per process; mirrors
 * {@link TrackerHealthAssembler}'s role.
 *
 * <p>Stateless: holds no fields, only assembles a fresh map from the gates handed to it on each
 * call.
 *
 * <p>Implements NFR-O3, UX6 of add-base-ref-resolution.
 */
public final class RemoteHealthAssembler {

    private RemoteHealthAssembler() {}

    /**
     * Assembles the {@code remote} section from {@code gates}' current state.
     *
     * @param gates every remote outage gate this daemon runs, keyed implicitly by their own
     *              {@link RemoteOutageGate#health()} target; never null
     * @return the assembled section, one entry per gate, keyed by target; never null
     */
    public static Map<String, RemoteHealth> assemble(Iterable<RemoteOutageGate> gates) {
        Map<String, RemoteHealth> result = new LinkedHashMap<>();
        for (RemoteOutageGate gate : gates) {
            RemoteOutageHealth health = gate.health();
            result.put(
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
        return Map.copyOf(result);
    }
}
