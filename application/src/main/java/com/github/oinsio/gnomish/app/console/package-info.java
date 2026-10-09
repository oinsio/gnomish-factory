/**
 * Application-layer console behaviour built on the {@code app.port.console} ports: {@link
 * com.github.oinsio.gnomish.app.console.DialogConsole}, the runner's output owner carrying the
 * human and machine write paths with no read side (design D4 of make-run-headless); and
 * {@link com.github.oinsio.gnomish.app.console.SystemConsoleIO}, the stream realization of
 * {@link com.github.oinsio.gnomish.app.port.console.ConsoleIO}.
 *
 * <p>Neither is an adapter (task 4.4, D12(a) of split-into-modules). {@code DialogConsole}
 * decorates an injected {@code ConsoleIO} with policy the use cases own; {@code SystemConsoleIO}
 * wraps two streams its caller hands it in a {@code BufferedReader} / {@code PrintStream} — a
 * pure JDK wrapper implementing a port, the same category as {@code ThreadSleeper} in
 * task 4.2. The environment itself — {@code System.in} / {@code System.out} /
 * {@code System.console()} — is named only at the composition root and in the {@code
 * @DoNotMutate} wiring methods that stand in for it.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.console;

import org.jspecify.annotations.NullMarked;
