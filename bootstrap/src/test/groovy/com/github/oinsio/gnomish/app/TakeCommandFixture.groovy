package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared {@link TakeCommand} construction for the reconcile specs: a real git-backed {@link
 * TaskGitFixture}, a fixed clock, and the accept-anything github tracker validator — the exact
 * {@link TakeCommandFactory#of} call that {@link TakeReconcileLifecycleSpecBase} and {@link
 * TwoInstanceTakeFixture} used to repeat verbatim aside from how each computes its {@link
 * FactoryProperties}.
 *
 * <p>Implements FR10, D10 of add-claim-heartbeat.
 */
trait TakeCommandFixture implements AppAssemblyFixture {

    TakeCommand newTakeCommand(
            FactoryProperties factoryProperties, Path worktreesRoot, Map<String, TrackerAdapterFactory> trackerFactories) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                trackerFactories,
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }
}
