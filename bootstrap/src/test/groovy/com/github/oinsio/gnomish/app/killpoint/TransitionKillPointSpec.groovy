package com.github.oinsio.gnomish.app.killpoint

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * The kill-point harness of design D13 of harden-task-branch-contract, driven as the table it is:
 * every multi-step transition of the branch medium, in both modes, killed after each of its durable
 * steps, picked up, and picked up again (M1, NFR-R1, UX1).
 *
 * <p>Creation is a row like the rest rather than a premise the others start from — see {@link
 * CreationKillPoints} for why the branch's first push earns its own windows.
 *
 * <p>What each row asserts, per kill window: the frozen state classifies to the shape the
 * `task-branch-contract` capability names for it, the pickup converges it to the transition's
 * settled shape, and the second recovery pass changes nothing durable. The rows run against real
 * git repositories and the real lifecycle writers of both media, so the host/container pair
 * (`.claude/rules/manual-sync-pairs.md`) is checked rather than assumed.
 *
 * <p>Deliberately NOT covered here: the tracker's own kill windows. The in-memory reference adapter
 * is atomic, so a frozen half-written tracker sequence cannot exist against it; those windows are
 * covered by fault injection in the GitHub adapter's own suite (FR19, task 9.1b).
 */
class TransitionKillPointSpec extends Specification implements KillPointWorlds {

    // Static, not @TempDir: the where-block closures are evaluated on Spock's data-provider
    // instance and run on the per-iteration one, so a per-instance temp directory would be unset
    // in exactly one of the two.
    private static final Path ROOT = Files.createTempDirectory('gnomish-kill-points')

    private static final AtomicInteger WORLDS = new AtomicInteger()

    def cleanupSpec() {
        Files.walk(ROOT).sorted(Comparator.reverseOrder()).forEach {
            Files.deleteIfExists(it)
        }
    }

    /** A fresh directory per world, so no kill window inherits another's repository. */
    private static Path nextRoot() {
        Path root = ROOT.resolve("world-${WORLDS.incrementAndGet()}")
        Files.createDirectories(root)
        root
    }

    def "M1, NFR-R1: #transition.name converges from every kill window, twice over"() {
        expect:
        KillPointHarness.verify(transition)

        where:
        transition << [
            ParkKillPoints.transition('host', { hostWorld(nextRoot()) }),
            ParkKillPoints.transition('container', {
                containerWorld(nextRoot())
            }),
            FinishKillPoints.transition('host', { hostWorld(nextRoot()) }),
            FinishKillPoints.transition('container', {
                containerWorld(nextRoot())
            }),
            DecisionKillPoints.transition('host', { hostWorld(nextRoot()) }),
            DecisionKillPoints.transition('container', {
                containerWorld(nextRoot())
            }),
            // One row, not a host/container pair: creation's windows are about publication to
            // origin, and both media publish the same way — a branch nobody but its author can see
            // is the same fact whichever writer built the STARTED commit.
            CreationKillPoints.transition('shared', {
                creationWorld(nextRoot())
            }),
        ]
    }
}
