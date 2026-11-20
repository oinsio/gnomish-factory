package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryApplication
import com.github.oinsio.gnomish.FactoryBoot
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitHostRounds
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds
import com.github.oinsio.gnomish.adapter.pipeline.GnomishDirPipelineSource
import com.github.oinsio.gnomish.app.ThreadSleeper
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
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

    // FR6, FR13, design D10 of add-plugin-architecture: settings validation must be uniform across
    // run modes — every mode loads through this same PipelineSource, so a check's malformed settings
    // fail at load in manual run exactly as in a real run (task 5.2 of
    // migrate-verify-to-executor-contract: the validators are the built-ins', keyed by their names).
    def "the wired PipelineSource carries the built-ins' params validators, in every run mode"() {
        given:
        def source = context.getBean(PipelineSource) as GnomishDirPipelineSource

        expect: 'the built-ins\' validators reached the loader, github-actions among them'
        source.checkProviderRegistry().containsKey('github-actions')
        source.checkProviderRegistry().keySet() == context.getBean('checkParamsValidatorRegistry', Map).keySet()
    }

    // FR6, FR13 of add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract: the
    // load grades each check's settings with its built-in's own validator, the map the root holds,
    // and reads no factory.check section (design D18, "Where a refusal at run assembly goes").
    def "the PipelineSource closes the built-ins' params validators over the load"() {
        given:
        def validators = new BuiltInCheckConfiguration().checkParamsValidatorRegistry()

        when:
        def source = new ManualRunConfiguration().pipelineSource([:], [:], validators,
        new FactoryProperties('instance', null, null, [:])) as GnomishDirPipelineSource

        then:
        source.checkProviderRegistry().keySet() == ['http', 'github-actions'] as Set
    }

    // FR4 of add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract: the check
    // equipment bean grades the operator's factory.check subsections, so a key no built-in reads —
    // the former `github` provider key among them — stops startup with a located error.
    def "the check equipment bean refuses a factory.check key no built-in reads"() {
        given:
        def properties = new FactoryProperties('instance', null, null, [github: ['api-url': 'https://api.github.com']])

        when:
        new ManualRunConfiguration().checkEquipment(new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(new VirtualClock()), MapSecretsProvider.NONE, properties)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('factory.check.github')
        e.message.contains('github-actions')
    }

    // FR1, design D3 of wire-host-mid-round-push; design D22 of migrate-verify-to-executor-contract:
    // the TaskGit bean carries the real host-git decoration — over a run's cell it builds the push
    // decorator around a round source and the snapshotting decoration around the stage chain,
    // never the undecorated pieces.
    def "the TaskGit bean's host-git decoration builds the push decorator and the snapshot decoration"() {
        given:
        def git = context.getBean(TaskGit)
        def source = Stub(RoundEnvironmentSource)
        def chain = Stub(StageExecutor)
        def cell = new CurrentRound()

        expect:
        git.midRoundPush() instanceof GitHostRounds
        git.midRoundPush().decorate(source, cell) instanceof MidRoundPushRounds
        !git.midRoundPush().snapshotting(chain, cell).is(chain)
    }

    def "context-independent beans are singletons: repeated lookups return the same instance"() {
        expect:
        context.getBean(FilesExistCheckRunner).is(filesExistCheckRunner)
        context.getBean(InMemoryAttemptPersistence).is(attemptPersistence)
    }
}
