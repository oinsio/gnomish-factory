/**
 * The runner: CLI argument parsing/validation, ad-hoc task synthesis, the outcome loop, and
 * exit-code wiring for {@code gnomish run} (design D10 of add-manual-run).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app;

import org.jspecify.annotations.NullMarked;
