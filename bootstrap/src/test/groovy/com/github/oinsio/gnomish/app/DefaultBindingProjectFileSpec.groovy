package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.HostBindingProvider
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.BooleanSupplier
import org.springframework.boot.context.properties.bind.Binder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The default-binding key binds from the project file (plugin/adapter-binding-registry): the
 * operator configuration loader admits {@code factory.bindings.default} from the resolved project's
 * {@code project.yaml}, Spring binds it, and the run plans on it — and the refusal that advises the
 * key names that same file, so following it as written works. The command-line form is refused by
 * {@code OperatorConfigViolationsSpec} ("A boundary key on the command line").
 *
 * <p>Implements FR6, NFR-S1 of add-project-registry; FR6 of fix-operator-blockers.
 */
class DefaultBindingProjectFileSpec extends Specification {

    private static final String HOST_DEFAULT = 'factory:\n  bindings:\n    default: host\n'

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // "Project file default binds host"
    def "FR6: the project file's factory.bindings.default: host plans every stage on the host binding"() {
        given:
        harness.projectConfig('widgets', HOST_DEFAULT)

        when:
        def plan = plan(load())

        then: 'every stage resolves to host, so no container is created'
        plan.mode() == SandboxModeSelector.Plan.Mode.HOST
        plan.segments()*.binding()*.configName() == ['host']
    }

    // "The error message's advice works as written"
    def "FR6, NFR-S1: following the missing-image refusal into the file it names starts the run in host mode"() {
        when: 'the container default is planned with no sandbox image'
        plan(load())

        then: 'the refusal names the project file line to add'
        def refusal = thrown(UsageException)
        def advice = refusal.message =~ /factory\.bindings\.default: host in (\S+)/
        advice.find()
        Path named = Path.of(advice.group(1))

        when: 'the operator adds that line to the file the message names, and reruns'
        Files.writeString(named, Files.readString(named) + HOST_DEFAULT)
        def rerun = plan(load())

        then:
        named == harness.home.project(new ProjectName('widgets')).config()
        rerun.mode() == SandboxModeSelector.Plan.Mode.HOST
    }

    private OperatorConfigLoaderHarness.Loaded load() {
        harness.load([
            'run',
            "--dir=$widgets".toString()
        ])
    }

    /** Plans a two-stage pipeline over the bindings and sandbox settings the loaded environment binds. */
    private static SandboxModeSelector.Plan plan(OperatorConfigLoaderHarness.Loaded loaded) {
        def binder = Binder.get(loaded.environment)
        SandboxModeSelector.plan(
                new PipelineDefinition('1', new AutonomyLimits(3), [stage('plan'), stage('build')]),
                binder.bindOrCreate('factory.bindings', BindingProperties),
                binder.bindOrCreate('factory.sandbox', SandboxProperties),
                AdapterBindingRegistry.ratified(
                        [
                            new HostBindingProvider(),
                            new ContainerBindingProvider()
                        ], BindingTrustTable.firstParty()),
                { true } as BooleanSupplier,
                loaded.context.getBean(RegisteredClone))
    }

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
