package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
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
 * <p>The stand-in is the committed preset {@code stall-everything}, reached through one
 * per-run link in the spec's directory: each invocation records that it began, then stalls (ADR
 * 0015). Implements FR2 of kill-expensive-mutants (design D4); FR24 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
trait StallingReadGitFixture {

    /**
     * The stand-in binary for {@code dir}: one per directory, stalling on every subcommand it is
     * asked for, so a read only ends on an interrupt.
     */
    Path stallingGit(Path dir) {
        Path link = readLink(dir)
        Files.isSymbolicLink(link) ? link : StallingGit.marked(link, 'stall-everything')
    }

    /** Blocks until a read is in flight, so an interrupt lands on the read and not before it. */
    void awaitReadStarted(Path dir) {
        StallingGit.awaitStall(readLink(dir))
    }

    private static Path readLink(Path dir) {
        dir.resolve('stalling-read-git')
    }
}
