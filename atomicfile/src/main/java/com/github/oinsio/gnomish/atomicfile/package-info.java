/**
 * The factory's one atomic file-write discipline: full content to a temp file in the target's own
 * directory, then an atomic rename onto the target, so a reader — including a salvaging resume
 * that arrives after a kill mid-write — sees either the complete previous content or the complete
 * new content, never a partial file.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no
 * other module of the factory, no Spring, no logging library, no Jackson, no domain type. The
 * constraint is load-bearing rather than tidy: the writer is consumed from the adapter layer (the
 * host-side {@code .gnomish-task/} writers) and from the application layer (the snapshot and
 * dashboard writers) alike, so any dependency added here would be pushed into both. The Gradle
 * layering gate states the same rule as data.
 *
 * <p><strong>What stays outside.</strong> The primitive owns the rename discipline only: what to
 * write, when to write it, and what a failure means are the callers' concerns. It does not log,
 * does not retry, and does not fsync — the durability point of the factory's task state is the
 * successful push, not the local write, per {@code docs/adr/0003-crash-consistency.md}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1 of add-serve-observability; FR5 of harden-task-branch-contract.
 */
@NullMarked
package com.github.oinsio.gnomish.atomicfile;

import org.jspecify.annotations.NullMarked;
