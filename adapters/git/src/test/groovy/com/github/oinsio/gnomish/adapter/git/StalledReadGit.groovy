package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Spock fixture: a scripted git ({@link ScriptedGit}) that blocks on every invocation, whatever its
 * subcommand, until the calling thread is interrupted — so a spec of a read-side seam (a tip lookup,
 * a ref enumeration) can drive "the read was cut off before it produced anything", the outcome no
 * real repository read can be asked to produce on demand. The interrupt answers
 * {@code INTERRUPTED}, as the runner does.
 *
 * <p>Replaces the stand-in fixture {@code StallingReadGitFixture} for the specs above the runner
 * (design D27 of supervise-daemon-loops-and-embed-dashboard).
 */
trait StalledReadGit {

    private final Map<Path, ScriptedGit> stalled = new ConcurrentHashMap<>()

    /** The blocking git for {@code dir}: one per directory, so a spec reads back what reached it. */
    ScriptedGit stallingGit(Path dir) {
        stalled.computeIfAbsent(dir) {
            new ScriptedGit().stalls([], new CountDownLatch(1))
        }
    }

    /**
     * Returns once {@code reader}'s read is in flight, so an interrupt lands on the read and not
     * before it; fails when the reader ends first (see {@link ScriptedGit#awaitCall}).
     */
    void awaitReadStarted(Path dir, Thread reader) {
        stallingGit(dir).awaitCall([], reader)
    }
}
