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
 * <p>Kept in sync with {@link StallingGitFixture}: both must strip a leading {@code -c}
 * option pair, hold {@code STALL_SECONDS} long enough that only an interrupt (never the
 * stand-in itself) can end the stall, and expose an {@code await*Started} poll loop with the
 * same 20s deadline / 20ms interval shape so a spec can block until the stand-in is in flight
 * before it interrupts.
 */
trait StallingReadGitFixture {

    /** Long enough that a read can only end on an interrupt, never on itself. */
    static final String STALL_SECONDS = '600'

    /** Appears once a read is in flight. */
    Path readStarted(Path dir) {
        dir.resolve('read-started')
    }

    /** Writes the stand-in binary into {@code dir}, stalling on every subcommand it is asked. */
    Path stallingGit(Path dir) {
        Path fakeGit = dir.resolve('stalling-git')
        fakeGit.toFile().text = """#!/bin/sh
while [ "\$1" = "-c" ]; do shift 2; done
touch ${readStarted(dir)}
sleep ${STALL_SECONDS}
"""
        fakeGit.toFile().executable = true
        return fakeGit
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
