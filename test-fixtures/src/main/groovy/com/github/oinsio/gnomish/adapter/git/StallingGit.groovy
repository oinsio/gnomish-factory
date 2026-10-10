package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path

/**
 * The selector of stalling stand-in {@code git} presets: a committed preset whose table stalls on
 * a chosen set of subcommands and answers the rest at once (ADR 0015). The stall mechanics — the
 * leading {@code -c} pairs dropped, the stall rows ahead of the answers, the {@code sleep} itself —
 * live once, in the stand-in library's script; a preset states only its scenario, and this class
 * hands it out only when its table really stalls.
 *
 * <p>Local commands answer before the remote one stalls: {@code GitProcessRunner} resolves a
 * mutating command's clone key through a local {@code rev-parse --git-common-dir} on the same
 * binary, unbounded by requirement, so a stand-in that stalled on every subcommand would sit out
 * its whole stall there, before the bounded command even started — which is why a preset stalls
 * on the subcommands it means and answers the rest.
 *
 * <p>History: before the library the same mechanics were spelled by hand nine times over, then by
 * a builder that wrote a fresh script per test (kill-expensive-mutants, design D4); every fresh
 * executable paid the operating system's first-run assessment, under PIT once per mutant
 * (supervise-daemon-loops-and-embed-dashboard, design D23).
 *
 * <p>Implements FR2, UX2 of kill-expensive-mutants; FR24 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
final class StallingGit {

    private StallingGit() {
    }

    /** A stalling preset, handed out by its committed path. */
    static Path git(String preset) {
        requireStall(preset)
        StandIn.git(preset)
    }

    /**
     * A per-run link at {@code at} to a stalling preset whose table records each stalled
     * invocation before it stalls, so {@link #awaitStall} can tell when the stall has begun. The
     * directory must exist.
     */
    static Path marked(Path at, String preset) {
        requireStall(preset)
        StandIn.link(at, preset)
    }

    /**
     * Blocks until {@code link} has recorded an invocation, so an interrupt lands on the stalled
     * command and not before it.
     */
    static void awaitStall(Path link) {
        long deadline = System.nanoTime() + 20_000_000_000L
        while (StandInLog.blocks(link).isEmpty() && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        assert !StandInLog.blocks(link).isEmpty(): 'the stand-in git never reached its stall'
    }

    private static void requireStall(String preset) {
        boolean stalls = StandIn.tables().rows(preset).any { List<String> row ->
            row.size() >= 2 && row[1] == 'stall'
        }
        if (!stalls) {
            throw new IllegalArgumentException("stand-in preset '${preset}' does not stall")
        }
    }
}
