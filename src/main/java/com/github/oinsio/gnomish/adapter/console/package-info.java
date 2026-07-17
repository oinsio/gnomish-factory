/**
 * Console I/O for manual runs: the single choke point through which all
 * interactive adapters and runner dialogs read from and write to the human
 * operator.
 *
 * <p>{@link com.github.oinsio.gnomish.adapter.console.ConsoleIO} is a dumb
 * read/print port with no knowledge of meta-commands or dialogs; higher-level
 * wrappers (e.g. {@code DialogConsole}) intercept {@code status} and other
 * cross-cutting concerns on top of it (design D1).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.console;

import org.jspecify.annotations.NullMarked;
