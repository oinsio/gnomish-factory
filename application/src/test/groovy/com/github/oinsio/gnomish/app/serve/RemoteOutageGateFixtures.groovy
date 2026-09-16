package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration

/**
 * Shared Spock fixture for a {@link RemoteOutageGate} that starts (and, absent
 * {@link RemoteOutageGate#openOnFailure}, stays) closed. {@link BaseRefGit#UNWIRED} is safe here
 * because a closed gate's {@code probeIfDue()} never calls it. Extracted because an identical
 * private {@code closedGate()} helper was hand-duplicated across {@code FeedCycleSpec} and
 * {@code FeedCyclePollFinishedDeclineSpec} (FR14 of add-base-ref-resolution).
 */
class RemoteOutageGateFixtures {

    static RemoteOutageGate closedGate() {
        new RemoteOutageGate(
                BaseRefGit.UNWIRED, Path.of('.'), new VirtualClock(), new Random(0), Duration.ofSeconds(1), Duration.ofMinutes(1))
    }
}
