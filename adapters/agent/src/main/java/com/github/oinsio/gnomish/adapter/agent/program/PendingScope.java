package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one binding of a {@code program} executor holds of the run's pending runs (design D14 of
 * migrate-verify-to-executor-contract, as amended 2026-11-13): the run's table, shared by every
 * binding of the program's registry entry; the subject the binding scopes it to; and the {@code HEAD}
 * an adoption of a recorded key expects. A stage round's binding and the conformance kit's candidate
 * have no subject and no expected {@code HEAD} ({@link #unscoped}); the fresh-box check binding scopes
 * the table to the request's {@link CheckRef} and expects the attempt commit it materialised the box at.
 * With no expected {@code HEAD}, an adoption expects the round's last registered commit.
 *
 * <p>A record, so the three values cost {@link ProgramExecutor} one constructor parameter.
 *
 * <p>Implements FR3, FR11 of migrate-verify-to-executor-contract.
 *
 * @param runs the run's table of pending runs of the program; never null
 * @param subject the check this binding serves, or {@code null} for a stage round and the kit
 * @param expectedHead the commit the binding put the working copy on, or {@code null} for the round's
 *     last registered commit
 */
public record PendingScope(
        PendingRuns runs,
        @Nullable CheckRef subject,
        @Nullable String expectedHead) {

    /** Refuses a missing table. */
    public PendingScope {
        Objects.requireNonNull(runs, "runs");
    }

    /**
     * A binding with no subject and no expected {@code HEAD}: a stage round's, or the kit's candidate's.
     *
     * @param runs the run's table; never null
     * @return the scope; never null
     */
    public static PendingScope unscoped(PendingRuns runs) {
        return new PendingScope(runs, null, null);
    }
}
