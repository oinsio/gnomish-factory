package com.github.oinsio.gnomish.subprocess;

/**
 * Where a {@link Drain} hands every chunk it reads, on the drain's own thread: a caller's retention
 * policy over the one read loop, such as the last lines of a command check's output (design D17 of
 * fix-terminal-receipt-convergence). The drain keeps the thread, the read, the bounded join and the
 * stop at the bound; the sink keeps only what its policy keeps.
 *
 * <p>Implements FR19 of fix-terminal-receipt-convergence.
 */
@FunctionalInterface
public interface DrainSink {

    /**
     * Takes the first {@code length} bytes of {@code chunk}; the array is reused after the call.
     *
     * @param chunk the read buffer
     * @param length how many of its bytes the read returned
     */
    void keep(byte[] chunk, int length);

    /**
     * Called once when the stream reached end-of-file — never after a broken read or a cut, so a
     * partial last line is kept only when the stream really ended there.
     */
    default void end() {
        // A sink with nothing pending at the end needs no signal.
    }
}
