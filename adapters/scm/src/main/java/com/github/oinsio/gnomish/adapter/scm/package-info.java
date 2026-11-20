/**
 * Core's SCM verdict built-ins and their one operator connection reader (FR13, NFR-S2, design D4,
 * D16 of migrate-verify-to-executor-contract): {@link
 * com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader} reads a built-in's {@code
 * factory.check.<name>} subsection, inline or by {@code connection:} profile, and admits its
 * endpoint through the egress guard.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.scm;

import org.jspecify.annotations.NullMarked;
