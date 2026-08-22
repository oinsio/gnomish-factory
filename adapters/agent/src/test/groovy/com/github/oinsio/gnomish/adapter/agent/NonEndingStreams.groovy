package com.github.oinsio.gnomish.adapter.agent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared stand-in stream for the drain-timeout specs (judge and executor
 * sides): a read that neither ends nor responds to an interrupt — only its own
 * {@code close} releases it, which is what the drain's teardown does. The spin
 * is bounded so a mutant that never closes it still frees the thread.
 */
final class NonEndingStreams {

    private NonEndingStreams() {
    }

    static InputStream nonEndingStream(AtomicBoolean released) {
        new InputStream() {
                    @Override
                    int read() {
                        long deadline = System.nanoTime() + 10_000_000_000L
                        while (!released.get() && System.nanoTime() <deadline) {
                            Thread.onSpinWait()
                        }
                        -1
                    }

                    @Override
                    void close() {
                        released.set(true)
                    }
                }
    }
}
