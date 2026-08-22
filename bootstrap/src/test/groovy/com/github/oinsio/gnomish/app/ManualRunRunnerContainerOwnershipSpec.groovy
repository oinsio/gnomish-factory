package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2 of add-serve-sandbox-lifecycle: the ownership label the COMPOSITION ROOT actually
 * stamps, asserted off the two bundles {@link ManualRunRunner}'s constructor really built —
 * {@code manual} for {@code gnomish run}, {@code tracked} for {@code take}/{@code serve}.
 *
 * <p>Every other spec of the take/serve path either binds {@code ContainerTakeSupport.hostOnly()}
 * or hand-builds a support lambda that only claims to mirror this wiring, so none of them can
 * catch the two labels being swapped or collapsed here. This one holds no stand-in for the
 * composition root: the runner is the production one, and each factory is invoked exactly as the
 * runners invoke it.
 *
 * <p>Daemon-free by construction, not by faking: building a container support bundle resolves the
 * project identity through {@code git remote} and assembles the environments seam, but runs no
 * Docker command — the first of those waits for a materialize this spec never asks for.
 */
class ManualRunRunnerContainerOwnershipSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    @TempDir
    Path worktreesRoot

    @TempDir
    Path homeDir

    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'seed\n')
        commitAll(cloneDir)
    }

    // FR2: the two entry points differ in exactly this label, and nothing else about the bundle.
    def "the composition root labels run's objects manual and take/serve's objects tracked"() {
        given:
        def runner = newRunner()

        when: 'each built factory is invoked exactly as its own runner invokes it'
        def runSupport = (ContainerRunSupport) runner.containerGitModeRunner.supportFactory()
                .create(cloneDir, 'T-run', segments(), sandbox(), testProperties(), pipeline(), [])
        def takeSupport = (ContainerRunSupport) runner.containerTakeSupport.containerSupportFactory()
                .create(cloneDir, 'T-take', segments(), sandbox(), testProperties(), pipeline(), [])

        then: 'the label each bundle stamps on every object it creates'
        runSupport.environments.ownershipMode() == OwnershipMode.MANUAL
        takeSupport.environments.ownershipMode() == OwnershipMode.TRACKED
    }

    private static SandboxProperties sandbox() {
        new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    // FR1, FR2 of add-serve-sandbox-lifecycle: delegates to the shared 21-collaborator factory
    // on AppAssemblyFixture (also used by ManualRunRunnerSpec), overriding only the two
    // arguments this spec's ownership assertion actually needs: a container image and the
    // container (non-host) binding mode.
    private ManualRunRunner newRunner() {
        newManualRunRunner(worktreesRoot, homeDir, sandbox(), new BindingProperties(null, [:]))
    }
}
