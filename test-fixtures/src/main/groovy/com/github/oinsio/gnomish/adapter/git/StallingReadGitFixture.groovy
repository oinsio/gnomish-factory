package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reusable Spock fixture: a stand-in {@code git} binary that stalls on every invocation,
 * regardless of subcommand, so a spec can drive "the read was killed before it produced
 * anything" — the outcome no real repository read can be asked to produce on demand.
 *
 * <p>Unlike {@link StallingGitFixture}, which answers every read and stalls only on
 * {@code push}, this fixture is for specs exercising a read-side seam (tip lookups, ref
 * enumeration) where every invocation the seam makes must stall.
 *
 * <p>The stall mechanics — stripping the leading {@code -c} pairs, the stall set and its length —
 * are owned by {@link StallingGit}; this trait states only its started marker.
 * Implements FR2 of kill-expensive-mutants (design D4).
 */
trait StallingReadGitFixture {

    /** Appears once a read is in flight. */
    Path readStarted(Path dir) {
        dir.resolve('read-started')
    }

    /**
     * Writes the stand-in binary into {@code dir}, stalling on every subcommand it is asked for
     * {@link StallingGit#DEFAULT_STALL}, so a read only ends on an interrupt.
     */
    Path stallingGit(Path dir) {
        new StallingGit().stallOnEverything().markOnStall(readStarted(dir)).write(dir)
    }

    /** Blocks until a read is in flight, so an interrupt lands on the read and not before it. */
    void awaitReadStarted(Path dir) {
        long deadline = System.nanoTime() + 20_000_000_000L
        while (!Files.exists(readStarted(dir)) && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        assert Files.exists(readStarted(dir)): 'the stand-in git never reached its read'
    }
}
