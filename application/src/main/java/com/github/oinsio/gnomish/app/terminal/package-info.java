/**
 * The one protocol every terminal transition with an external effect follows (FR10, design D5 of
 * harden-task-branch-contract): durable intent before the effect, receipt after it, the target
 * probed before any re-drive, and the destructive step last of all.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.terminal;

import org.jspecify.annotations.NullMarked;
