package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path

/**
 * The production {@link ContainerSupportFactory} a spec passes wherever the composition root binds
 * the real container bundle (task 4.4, FR12b of split-into-modules). The runners take the factory
 * injected now, so the specs that want the real thing say so here once instead of repeating the
 * wiring — and the daemon-free specs keep binding their own scripted-docker factory.
 */
final class ContainerSupportFixture {

    private ContainerSupportFixture() {}

    /** The real per-run container support, over the real Docker runtime. */
    static ContainerSupportFactory real() {
        { Path cloneDir, String taskId, List<Segment> segments, SandboxProperties sandbox, FactoryProperties factory, List<String> credentialEnvVarsToScrub ->
            ContainerRunSupport.create(cloneDir, taskId, segments, sandbox, factory, credentialEnvVarsToScrub)
        } as ContainerSupportFactory
    }
}
