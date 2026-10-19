package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fixtures the {@link Drain} specs share over {@link FakeProcess} (design D6 of
 * make-pipe-reads-non-parking): a child that already said its words and exited, and a wait that
 * holds until a drain has kept what it read, made from the pipe's own counts rather than a sleep.
 */
trait DrainSpecSupport {

    /** A child that wrote {@code text} to stdout and exited with 0, its pipes released. */
    FakeProcess exitedWith(String text) {
        FakeProcess process = FakeProcess.running()
        process.writeStdout(text)
        process.exit(0)
        process
    }

    /**
     * Waits until a drain of {@code process}'s stdout read bytes and queried the pipe again — the
     * query after a read follows the hand-off of what it read, so the bytes are kept by then.
     */
    void awaitKept(FakeProcess process) {
        assert process.awaitStdoutRead(Duration.ofSeconds(10))
        CountDownLatch queried = new CountDownLatch(1)
        process.stdoutPipe().onQuery {
            queried.countDown()
        }
        assert queried.await(10, TimeUnit.SECONDS)
    }
}
