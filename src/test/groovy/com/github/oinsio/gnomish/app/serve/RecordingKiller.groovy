package com.github.oinsio.gnomish.app.serve

import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake {@link ProcessTreeKiller} that records how many times it was invoked, so shutdown
 * sequencing can be asserted on without spawning or killing real OS processes. Shared between
 * {@code ServeShutdownSpec} and {@code ServeShutdownWiringSpec}, both of which prove parts of the
 * same FR11/D9 shutdown sequence.
 */
class RecordingKiller implements ProcessTreeKiller {
    final AtomicInteger calls = new AtomicInteger()

    @Override
    void killDescendants() {
        calls.incrementAndGet()
    }
}
