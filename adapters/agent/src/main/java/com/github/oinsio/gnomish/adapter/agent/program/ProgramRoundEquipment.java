package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.ProgramCredentialGrant;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What every {@code program} run of one factory run is equipped with, stable for the run's lifetime
 * (FR5, NFR-R2, NFR-S1 of define-executor-contract): the factory's bounds, the time equipment the
 * bounds are measured and waited on, the run allowlist's program credential grant — which resolves a
 * run's granted names to values at each launch and exposes no value source — and the host directory
 * each run's stderr directory is made in.
 *
 * <p>Implements FR5, NFR-R2, NFR-S1 of define-executor-contract.
 *
 * @param bounds the factory's bounds on a program run; never null
 * @param time the run's time equipment; never null
 * @param grant the run allowlist's program credential grant ({@code ChildEnvAllowlist#programGrant});
 *     never null
 * @param stderrParent the host directory a run's stderr directory is created in; never null
 */
public record ProgramRoundEquipment(
        ProgramBounds bounds, TimeEquipment time, ProgramCredentialGrant grant, Path stderrParent) {

    /** Refuses a missing member. */
    public ProgramRoundEquipment {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(stderrParent, "stderrParent");
    }
}
