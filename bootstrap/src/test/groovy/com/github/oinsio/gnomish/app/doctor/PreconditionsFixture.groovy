package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.app.SandboxModeSelector
import com.github.oinsio.gnomish.app.ServePreconditions
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties

/**
 * The real {@link ServePreconditions} over doubles of the ports it asks — the ones a doctor check
 * reads its verdict from (design D1 of add-doctor-command) — with the selector the composition root
 * builds over the classpath's bindings.
 */
final class PreconditionsFixture {

    static final String IMAGE = 'gnomish/sandbox:1'

    private PreconditionsFixture() {}

    static SandboxProperties sandbox(String image = IMAGE) {
        new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
    }

    static ServePreconditions preconditions(
            TaskBranchGit branches, SandboxImageReadiness readiness, boolean runtimeAnswers = true,
            String binding = BindingNames.CONTAINER, String image = IMAGE) {
        def selector = new SandboxModeSelector(new BindingProperties(binding, [:]), sandbox(image), DiscoveredBindings.real(),
        { -> runtimeAnswers } as ContainerRuntimeProbe)
        new ServePreconditions(branches, selector, readiness, sandbox(image))
    }
}
