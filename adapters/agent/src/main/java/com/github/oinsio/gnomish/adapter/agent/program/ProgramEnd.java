package com.github.oinsio.gnomish.adapter.agent.program;

import java.time.Duration;

/**
 * How one {@code program} process ended, as the factory saw it before reading any file (NFR-R2 of
 * define-executor-contract): it exited on its own, one of the factory's bounds expired and its
 * process tree was killed, or the waiting thread was interrupted and the tree killed with the flag
 * left set. Only {@link Exited} has a result to read.
 *
 * <p>Implements NFR-R2 of define-executor-contract.
 */
sealed interface ProgramEnd {

    /**
     * The process exited within its bounds.
     *
     * @param exitCode its exit code, which carries no meaning to the engine (FR7)
     * @param wallTime the measured start-to-exit span
     */
    record Exited(int exitCode, Duration wallTime) implements ProgramEnd {}

    /**
     * A bound expired and the process tree was killed.
     *
     * @param bound the bound's name, {@code silence} or {@code overall}
     * @param value the bound's value
     */
    record BoundExpired(String bound, Duration value) implements ProgramEnd {}

    /** The wait was interrupted; the process tree was killed and the interrupt flag left set. */
    record Interrupted() implements ProgramEnd {}
}
