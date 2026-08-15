package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed and first-tier-validated flags of one {@code gnomish serve} invocation, produced by
 * {@link ServeArgumentsParser} (task 5.1 of add-factory-serve). Unlike {@link TakeArguments},
 * {@code serve} has no {@code <ref>} — it processes the whole ready queue, not one task — and no
 * {@code --interactive}: serve is unconditionally non-interactive (FR4), so no interactive mode is
 * even parsed.
 *
 * <p>Implements FR2, FR4, D3 of add-factory-serve.
 *
 * @param dir the target project directory; defaults to the current working directory when {@code
 *     --dir} is absent, matching {@link TakeArguments#dir()}; unresolved, not checked for
 *     existence here
 * @param slots the {@code --slots} override of {@code
 *     com.github.oinsio.gnomish.ServeProperties#slots()} (design D3), or {@code null} to use the
 *     configured default; the parser rejects a non-positive value before it ever reaches here
 * @param drain {@code --drain}: requests drain-mode shutdown; carried here as a plain flag —
 *     acting on it is task 5.4's job, not this record's or its parser's
 */
record ServeArguments(Path dir, @Nullable Integer slots, boolean drain) {}
