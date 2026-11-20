package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.executorcontract.Executor;
import java.util.Objects;

/**
 * An executor bound for one check request and what releases the environment it was bound in (design
 * D11 as amended 2026-11-14, D18 as amended 2026-11-13 of migrate-verify-to-executor-contract): a
 * fresh-box {@code program} check's binding materializes a box per request, and the check round
 * releases it when the request ends — its {@code start}, or its adoption and poll alike, so a
 * recorded key is adopted and polled in one box. A built-in's binding holds no environment and
 * releases nothing ({@link #unboxed}).
 *
 * <p>Implements FR3, FR11 of migrate-verify-to-executor-contract.
 *
 * @param executor the bound executor; never null
 * @param release what the check round runs when its request ends; never null
 */
public record BoundCheck(Executor executor, Runnable release) {

    /** Refuses a missing member. */
    public BoundCheck {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(release, "release");
    }

    /**
     * An executor bound in no environment of its own.
     *
     * @param executor the bound executor; never null
     * @return the bound check, releasing nothing; never null
     */
    public static BoundCheck unboxed(Executor executor) {
        return new BoundCheck(executor, () -> {});
    }
}
