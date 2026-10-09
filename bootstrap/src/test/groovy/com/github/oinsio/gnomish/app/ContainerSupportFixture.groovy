package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Path

/**
 * The production {@link ContainerSupportFactory} a spec passes wherever the composition root binds
 * the real container bundle (task 4.4, FR12b of split-into-modules). The runners take the factory
 * injected now, so the specs that want the real thing say so here once instead of repeating the
 * wiring — and the daemon-free specs keep binding their own scripted-docker factory.
 */
final class ContainerSupportFixture {

    private ContainerSupportFixture() {}

    /**
     * The real per-run container support, over the real Docker runtime, {@code manual}-owned.
     *
     * <p>{@code epochs} is the tenure record of the bundle the spec drives this support beside —
     * {@code git.epochs()}, never a record minted here (FR5, design D3 of fix-claim-epoch-fence).
     * It is a required argument for the reason the production bundle's is: a container run whose
     * commits stamp from one record while the claim fills another is exactly the assembly this
     * change makes unbuildable. On the {@code run} path the record is simply never filled.
     *
     * <p>{@code sandbox} and {@code factory} are the installation's settings the factory holds for
     * every run it builds (design D12 of make-checkpoint-gate-durable) — the runners no longer
     * carry them.
     */
    static ContainerSupportFactory real(ClaimEpochSource epochs, SandboxProperties sandbox, FactoryProperties factory) {
        forOwnership(OwnershipMode.MANUAL, epochs, sandbox, factory)
    }

    /**
     * As {@link #real}, but {@code tracked}-owned — the ownership label a {@code take}/{@code
     * serve} dispatch of an already-claimed tracker task carries, as opposed to {@code run}'s
     * {@code manual} label.
     */
    static ContainerSupportFactory tracked(
            ClaimEpochSource epochs, SandboxProperties sandbox, FactoryProperties factory) {
        forOwnership(OwnershipMode.TRACKED, epochs, sandbox, factory)
    }

    private static ContainerSupportFactory forOwnership(
            OwnershipMode ownershipMode, ClaimEpochSource epochs, SandboxProperties sandbox, FactoryProperties factory) {
        // The check providers' credential declarations are resolved by the composition root and
        // handed down (FR17, D11 of add-plugin-architecture); these specs configure no check
        // provider, so the declared set and the registry are empty.
        new ContainerRunSupportFactory([], [:], ownershipMode, epochs, sandbox, factory)
    }

    /**
     * One run's real container support, built directly rather than through a runner — for the
     * lifecycle specs that drive the bundle's own environments. No check provider is configured
     * unless {@code checkCredentialEnvVars} names one; the pipeline declares no check, so it
     * contributes none either. {@code epochs} is the caller's to choose, as for {@link #real}: a
     * spec that never claims says so by passing {@code ClaimEpochSource.NONE} itself.
     */
    static ContainerRunSupport direct(Path cloneDir, String taskId, List<Segment> segments, SandboxProperties sandbox,
            FactoryProperties factory, OwnershipMode ownershipMode, ClaimEpochSource epochs,
            List<String> checkCredentialEnvVars = []) {
        (ContainerRunSupport) new ContainerRunSupportFactory(checkCredentialEnvVars, [:], ownershipMode, epochs, sandbox, factory)
        .create(cloneDir, taskId, segments, new PipelineDefinition('1', new AutonomyLimits(3), []), [])
    }
}
