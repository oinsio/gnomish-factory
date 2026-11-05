/**
 * The shared command-run core (design D2 of add-command-executor): one way to run a declared
 * command line through the task environment with a timeout and a bounded output tail, used by the
 * verify {@code command} check and the {@code command} stage executor alike. It classifies nothing.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.command;

import org.jspecify.annotations.NullMarked;
