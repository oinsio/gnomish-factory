package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * {@link RemoteOutageGate}'s observability collaborators (task 7.4 of add-base-ref-resolution),
 * bundled into one parameter object per the project's parameter-count limit
 * (process-invariants.md) — the gate's full constructor otherwise runs to eleven positional
 * arguments. This is deliberately a plain data carrier: the gate still owns every decision about
 * when to log, count, or call back, it just reads the "who/how" from here instead of from six
 * separate constructor slots.
 *
 * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution.
 *
 * @param target this gate's remote-target identity, named in every log line, snapshot entry and
 *     ledger line; never blank
 * @param suppressor the edge-logging owner for this target's failed-probe streak; never null
 * @param sustainedOpenThreshold how long the gate may stay open before the one-shot ERROR fires;
 *     never null, positive
 * @param onTransition called after every open/close transition (never after a mere probe) so the
 *     caller can trigger an immediate snapshot write; never null
 * @param onClosedOutage called exactly once per closed outage with its summary, so the caller can
 *     append the {@code remoteOutage} ledger line; never null
 */
record RemoteOutageWiring(
        String target,
        RepeatSuppressor suppressor,
        Duration sustainedOpenThreshold,
        Runnable onTransition,
        Consumer<RemoteOutageClosedOutage> onClosedOutage) {

    /** The production defaults: {@link RemoteOutageGate#DEFAULT_TARGET}, no-op callbacks. */
    static RemoteOutageWiring defaults() {
        return new RemoteOutageWiring(
                RemoteOutageGate.DEFAULT_TARGET,
                RepeatSuppressor.system(),
                RemoteOutageGate.DEFAULT_SUSTAINED_OPEN_THRESHOLD,
                () -> {},
                ignored -> {});
    }
}
