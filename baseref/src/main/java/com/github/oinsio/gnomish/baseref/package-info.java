/**
 * The factory's one base-resolution policy: which ref a new task branch starts from. Inputs are
 * plain values — the project's allowed bases, the task's already-classified base
 * designator, the configured default, the repository default branch as the remote reported it, and
 * the invocation mode. The output is a decision {@code (ref, rule, reason)} or an underdetermined
 * verdict the caller escalates on.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring, no Jackson, no logging API. Unlike the other dependency-free
 * leaves, whose emptiness exists to spare their consumers transitive coupling, this one's emptiness
 * is a security property (NFR-S3): a policy that can import nothing cannot reach a subprocess, a
 * git remote, a tracker label, or a configuration format, so the only thing a repository-provided
 * input can drive here is pattern matching over values. The Gradle layering gate states the same
 * rule as data, with an allowlist that is empty.
 *
 * <p><strong>What stays outside.</strong> Default-branch discovery ({@code ls-remote --symref}) and
 * the refresh fetch live in the git adapter; parsing the {@code task-branch.base} block lives in the pipeline
 * loader, which maps its DTO into the value types here; orchestration, the base pin and the
 * escalation report live in the application layer. The tracker port's own three-shape designator
 * type is mapped onto {@link com.github.oinsio.gnomish.baseref.BaseDesignator} by the application
 * layer, because this package may import neither.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1, FR4, FR5, FR8, FR10, NFR-S3 of add-base-ref-resolution.
 */
@NullMarked
package com.github.oinsio.gnomish.baseref;

import org.jspecify.annotations.NullMarked;
