/**
 * A small, extraction-ready library for building and reading git commits over <em>bare</em> object
 * storage — no working copy, no checkout, no hook execution (design D19 of add-sandbox-core). The
 * factory uses it for its four lifecycle write points (task-branch creation, resume decision, task
 * outcome, Completed cleanup): each is a plumbing commit built by reading a parent tree into a
 * private temporary index, applying {@link com.github.oinsio.gnomish.gitobjects.TreeEdit}s,
 * {@code write-tree} + {@code commit-tree}, and advancing the ref with git's atomic
 * compare-and-swap {@code update-ref} — a concurrently moved tip fails the write, never force.
 *
 * <p>The package is a library by construction: it imports only the JDK, the SLF4J API, and the
 * factory's root-package {@link com.github.oinsio.gnomish.DoNotMutate} PIT marker — never adapter,
 * app, domain, status, usage, Spring, or Jackson types (pinned by {@code GitObjectsBoundarySpec}) —
 * and every commit id is deterministic for fixed inputs because
 * the caller supplies identity, timestamps, and message. {@link
 * com.github.oinsio.gnomish.gitobjects.GitObjects} and its request/edit records are the public API;
 * the git-subprocess plumbing stays package-private. ArchUnit pins the boundary in both directions.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
@NullMarked
package com.github.oinsio.gnomish.gitobjects;

import org.jspecify.annotations.NullMarked;
