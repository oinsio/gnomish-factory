/**
 * The factory's one subprocess supervision discipline: output drained concurrently with the
 * running process, an optional hard deadline on the wait, and — on expiry or interruption — a
 * two-phase termination of the whole process tree followed by a reap, so no process or descendant
 * survives its invocation. How an invocation ended ({@link
 * com.github.oinsio.gnomish.subprocess.Termination}) is named separately from the exit code, so no
 * caller has to read a sentinel code to tell a command that ran and failed from one that was
 * killed or interrupted.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no
 * other module of the factory, no Spring, no logging library, no Jackson, and in particular no
 * domain type (not even the domain {@code Clock}: the deadline is a plain {@link
 * java.time.Duration} handed to {@link java.lang.Process#waitFor(long, java.util.concurrent.TimeUnit)},
 * so there is no clock to inject). The constraint is not tidiness. {@code gitobjects} is
 * extraction-ready only while everything it reaches is JDK-only (design D19 of add-sandbox-core),
 * and it depends on this module — so any import added here breaks that module's build rather than
 * quietly eroding a documented promise. The Gradle layering gate states the same rule as data.
 *
 * <p><strong>What stays outside.</strong> Mechanics unify; policy stays at the call sites (NG4).
 * This package does not log — it returns outcomes and lets its callers decide what to say about
 * them — and it does not own stdout caps, stdin feeds, output tails, credential scrubbing, or the
 * classification of an exit code. {@link com.github.oinsio.gnomish.subprocess.CaptureRunner} is a
 * convenience for the capture-shaped callers only; streaming callers drive {@link
 * com.github.oinsio.gnomish.subprocess.ProcessSupervisor} directly and keep their own readers.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR2, FR3, FR6, FR9, NFR-R1, NFR-R2, NFR-S3 of bound-subprocess-commands.
 */
@NullMarked
package com.github.oinsio.gnomish.subprocess;

import org.jspecify.annotations.NullMarked;
