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
 * <p>The optional {@code sandboxLifecyclePass} defaults to {@link SandboxLifecyclePass#NONE}, which
 * every take spec but the startup-sweep one relies on so the sweep call site stays invisible to them.
 *
 * <p>Nothing here wires a tenure record: {@link TaskGitFixture#real} builds one book per bundle,
 * and the command resolves its tracker through {@code TrackerResolution.resolveTracker}, which
 * wraps it in the recording decorator over that same book (FR4, design D2/D3 of
 * fix-claim-epoch-fence). So a command built here stamps and records exactly as production does —
 * one book per {@code newTakeCommand} call, because the book is per-process and this method builds
 * one instance, so the two-instance fixtures get two books, as two factory processes would.
 *
 * <p>Implements FR10, D10 of add-claim-heartbeat; FR6 of fix-claim-epoch-fence.
 */
trait TakeCommandFixture implements AppAssemblyFixture {

    TakeCommand newTakeCommand(
            FactoryProperties factoryProperties, Path worktreesRoot, Map<String, TrackerAdapterFactory> trackerFactories,
            TakeCommandSeams seams = TakeCommandSeams.DEFAULTS,
            SandboxLifecyclePass sandboxLifecyclePass = SandboxLifecyclePass.NONE) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                trackerFactories,
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                seams,
                sandboxLifecyclePass, ContainerTakeSupport.hostOnly())
    }
}
