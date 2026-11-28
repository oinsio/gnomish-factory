package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.executorcontract.Executor;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An executor bound for one check request, its adoption role, and what releases the environment it
 * was bound in (design D11 as amended 2026-11-14, D14 as amended 2026-11-13, D18 as amended
 * 2026-11-13 of migrate-verify-to-executor-contract): a fresh-box {@code program} check's binding
 * materializes a box per request, and the check round releases it when the request ends — its {@code
 * start}, or its adoption and poll alike, so a recorded key is adopted and polled in one box. A
 * built-in's binding holds no environment and releases nothing ({@link #unboxed}).
 *
 * <p>The adoption role travels beside the executor, taken by the binding from the executor it built
 * before any decorator wrapped it — the guard probe's (design D13 of extract-agent-round-runner) here,
 * the pin-check's later — so no decorator takes or forwards the role, and the check round stays the
 * one caller of {@link PendingAdoption#adopt}.
 *
 * <p>Implements FR3, FR11 of migrate-verify-to-executor-contract; FR15 of extract-agent-round-runner.
 *
 * @param executor the bound executor, behind whatever decorators its binding applied; never null
 * @param adoption the same executor's adoption role as its binding built it, or {@code null} for an
 *     executor that does not take the role
 * @param release what the check round runs when its request ends; never null
 */
public record BoundCheck(Executor executor, @Nullable PendingAdoption adoption, Runnable release) {

    /** Refuses a missing member. */
    public BoundCheck {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(release, "release");
    }

    /**
     * An executor bound in no environment of its own, taking no adoption role.
     *
     * @param executor the bound executor; never null
     * @return the bound check, releasing nothing; never null
     */
    public static BoundCheck unboxed(Executor executor) {
        return new BoundCheck(executor, null, () -> {});
    }
}
