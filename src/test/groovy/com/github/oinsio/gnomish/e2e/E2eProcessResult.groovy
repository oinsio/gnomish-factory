package com.github.oinsio.gnomish.e2e

/**
 * The outcome of one {@link E2eProcessHarness#run} invocation (task 9.1, M1): the
 * real process's exit code and its stdout/stderr, captured separately so a
 * scenario can assert on the dialog (stdout) independently of diagnostics
 * (stderr) — e.g. UX3's farewell line and NFR-O2's WARN+/ERROR split.
 *
 * <p>M1 of add-manual-run.
 *
 * @param exitCode the process's exit code
 * @param stdout everything the process wrote to stdout, verbatim
 * @param stderr everything the process wrote to stderr, verbatim
 */
record E2eProcessResult(int exitCode, String stdout, String stderr) {}
