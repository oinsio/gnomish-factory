package com.github.oinsio.gnomish.subprocess;

/**
 * The outcome of one supervised wait: how the invocation ended, and the exit value the process
 * finally carried.
 *
 * <p>{@code exitCode} is authoritative only when {@code termination} is {@link Termination#EXITED}.
 * After a kill it is whatever the OS recorded for the terminated process (on Unix, {@code 143} for
 * a cooperative terminate, {@code 137} for a forced one) and after an interrupt it may be {@code
 * -1} if the reap itself could not complete — diagnostic context, never a signal to branch on.
 * Callers branch on {@code termination} first.
 *
 * <p>Implements FR6 of bound-subprocess-commands.
 *
 * @param termination how the invocation ended
 * @param exitCode the process's exit value; meaningful on {@link Termination#EXITED}
 */
public record Supervision(Termination termination, int exitCode) {}
