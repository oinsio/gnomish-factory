package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
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
     */
    static ContainerSupportFactory real(ClaimEpochSource epochs) {
        forOwnership(OwnershipMode.MANUAL, epochs)
    }

    /**
     * As {@link #real}, but {@code tracked}-owned — the ownership label a {@code take}/{@code
     * serve} dispatch of an already-claimed tracker task carries, as opposed to {@code run}'s
     * {@code manual} label.
     */
    static ContainerSupportFactory tracked(ClaimEpochSource epochs) {
        forOwnership(OwnershipMode.TRACKED, epochs)
    }

    private static ContainerSupportFactory forOwnership(OwnershipMode ownershipMode, ClaimEpochSource epochs) {
        { Path cloneDir, String taskId, List<Segment> segments, SandboxProperties sandbox, FactoryProperties factory, definition, List<String> credentialEnvVarsToScrub ->
            // The check providers' credential declarations are resolved by the composition root and
            // handed down (FR17, D11 of add-plugin-architecture); these specs configure no check
            // provider, so the declared set is empty.
            ContainerRunSupport.create(
            cloneDir, taskId, segments, sandbox, factory,
            List.<String> of(), credentialEnvVarsToScrub, ownershipMode, epochs)
        } as ContainerSupportFactory
    }
}
