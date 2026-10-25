package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path

/**
 * A daemon's full set of conditions, closed, on virtual time, for a spec that builds a slot runner
 * or a feed without asserting on the conditions themselves (design D19 of add-sandbox-base-image):
 * the production factory, the remote's probe unwired (a closed condition never probes) and every
 * precondition's recheck passing.
 */
class DaemonConditionsFixture {

    static DaemonConditions closed() {
        RemoteOutageGates.forServe(BaseRefGit.UNWIRED, Path.of('.'),
                new ServeProperties(0, null, null, null, null, null, null, null, null, null), new VirtualClock(),
                DirtyNotifier.NOOP, RemoteOutageLedgerSink.NONE, { kind ->
                    Optional.empty()
                } as PreconditionRecheck)
    }
}
