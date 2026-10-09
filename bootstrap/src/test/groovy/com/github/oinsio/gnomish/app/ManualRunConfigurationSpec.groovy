package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryApplication
import com.github.oinsio.gnomish.FactoryBoot
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds
import com.github.oinsio.gnomish.adapter.pipeline.GnomishDirPipelineSource
import com.github.oinsio.gnomish.app.ThreadSleeper
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import java.nio.file.Path
import java.time.InstantSource
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * D10 of add-manual-run: {@link ManualRunConfiguration} assembles every {@code gnomish run}
 * collaborator that needs no per-invocation data as a Spring bean — the context-independent
 * half of the {@code EnginePorts} bean graph. The full {@link FactoryApplication} context is
 * booted (mirroring FactoryApplicationSpec) since these beans are component-scanned under it;
 * this also proves the configuration coexists with the untouched no-args bootstrap (task 7.12).
 */
class ManualRunConfigurationSpec extends Specification {

    @Shared
    @TempDir
    Path operatorHomeDir

    @Shared
    OperatorHomeFixture operatorHome

    @Shared
    ConfigurableApplicationContext context

    @Shared
    FilesExistCheckRunner filesExistCheckRunner

    @Shared
    ShellCommandCheckRunner shellCommandCheckRunner

    @Shared
    InMemoryAttemptPersistence attemptPersistence

    @Shared
    InstantSource instantSource

    @Shared
    TimeEquipment timeEquipment

    @Shared
    SystemConsoleIO systemConsoleIO

    @Shared
    PipelineStartup pipelineStartup

    @Shared
    RunExitCodeMapper runExitCodeMapper

    @Shared
    AdHocTaskSynthesizer adHocTaskSynthesizer

    @Shared
    ManualRunRunner manualRunRunner

    // Design D8 of add-project-registry: booted through the CommandExit argument registration,
    // against a factory home of the spec's own.
    def setupSpec() {
        operatorHome = OperatorHomeFixture.install(operatorHomeDir.resolve('home'))
        context = FactoryBoot.boot()
        filesExistCheckRunner = context.getBean(FilesExistCheckRunner)
        shellCommandCheckRunner = context.getBean(ShellCommandCheckRunner)
        attemptPersistence = context.getBean(InMemoryAttemptPersistence)
        instantSource = context.getBean('instantSource', InstantSource)
        timeEquipment = context.getBean(TimeEquipment)
        systemConsoleIO = context.getBean(SystemConsoleIO)
        pipelineStartup = context.getBean(PipelineStartup)
        runExitCodeMapper = context.getBean(RunExitCodeMapper)
        adHocTaskSynthesizer = context.getBean(AdHocTaskSynthesizer)
        manualRunRunner = context.getBean(ManualRunRunner)
    }

    def cleanupSpec() {
        context?.close()
        operatorHome?.close()
    }

    def "the context boots with every context-independent EnginePorts collaborator wired"() {
        expect:
        context != null
        filesExistCheckRunner != null
        shellCommandCheckRunner != null
        attemptPersistence != null
        instantSource != null
        timeEquipment != null
        systemConsoleIO != null
    }

    def "FR22 of supervise-daemon-loops-and-embed-dashboard: real time is one equipment, and the instant source bean is its clock"() {
        expect: 'the instant source bean is the very clock of the one time equipment'
        instantSource.is(timeEquipment.clock())

        and: 'the equipment waits on the real sleeper, and no other sleeper bean exists to inject'
        timeEquipment.sleeper() instanceof ThreadSleeper
        context.getBeansOfType(Sleeper).isEmpty()
        context.getBeansOfType(TimeEquipment).size() == 1
    }

    def "the runner-level components (task 7.1-7.9) are present in the same context"() {
        expect:
        pipelineStartup != null
        runExitCodeMapper != null
        adHocTaskSynthesizer != null
    }

    def "the ApplicationRunner entrypoint is registered exactly once"() {
        expect:
        manualRunRunner != null
        context.getBeansOfType(ApplicationRunner).size() == 1
    }

    // FR6, FR13, design D10 of add-plugin-architecture: provider validation must be uniform across
    // run modes — manual run replaces the external seam wholesale with the interactive client, but
    // it loads through this same PipelineSource, so a manifest naming an undiscovered provider
    // fails at load there exactly as in a real run.
    def "the wired PipelineSource carries the discovered check providers, in every run mode"() {
        given:
        def source = context.getBean(PipelineSource) as GnomishDirPipelineSource

        expect: 'the discovered providers reached the loader, github among them'
        source.checkProviderRegistry().containsKey('github')
        source.checkProviderRegistry().keySet() == context.getBean('checkClientRegistry', Map).keySet()
    }

    // FR3, design D3 of remove-interactive-console: the configured set the load grades `external`
    // checks against is the operator's `factory.check` sections — the one fact this root alone holds.
    def "the PipelineSource grades external checks against the configured factory.check sections"() {
        given:
        def properties = new FactoryProperties('instance', null, null, [github: [:], http: [:]])

        when:
        def source = new ManualRunConfiguration().pipelineSource([:], [:], properties) as GnomishDirPipelineSource

        then:
        source.configuredCheckProviders() == ['github', 'http'] as Set
    }

    // FR1, design D3 of wire-host-mid-round-push: the TaskGit bean carries the real mid-round
    // push decoration — applying it to a round source yields the MidRoundPushRounds decorator,
    // never null and never the undecorated source.
    def "the TaskGit bean's mid-round push decoration builds the push decorator"() {
        given:
        def git = context.getBean(TaskGit)
        def source = Stub(RoundEnvironmentSource)

        expect:
        git.midRoundPush().apply(source) instanceof MidRoundPushRounds
    }

    def "context-independent beans are singletons: repeated lookups return the same instance"() {
        expect:
        context.getBean(FilesExistCheckRunner).is(filesExistCheckRunner)
        context.getBean(InMemoryAttemptPersistence).is(attemptPersistence)
    }
}
