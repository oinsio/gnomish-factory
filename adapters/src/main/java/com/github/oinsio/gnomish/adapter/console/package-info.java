/**
 * The interactive adapters: engine ports whose realization is a human operator
 * answering at the terminal — the stage executor, the judge voter, the external
 * check client, the stage briefing and the findings dialog.
 *
 * <p>All of them read and write through {@code app.console.DialogConsole}, the
 * single choke point that intercepts {@code status} and other cross-cutting
 * concerns on top of the dumb {@link
 * com.github.oinsio.gnomish.app.port.console.ConsoleIO} read/print port (design
 * D1). Both the port and its stream realization ({@code
 * app.console.SystemConsoleIO}) sit below this package, in the application
 * layer — nothing here is their owner (task 4.4, D12 of split-into-modules).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.console;

import org.jspecify.annotations.NullMarked;
