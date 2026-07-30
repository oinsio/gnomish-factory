/**
 * The {@code gnomish take} runner: claim acquisition and revocation checking, abort/backoff
 * handling, and outcome/exit-code mapping around one {@code engine.run(...)} call.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.take;

import org.jspecify.annotations.NullMarked;
