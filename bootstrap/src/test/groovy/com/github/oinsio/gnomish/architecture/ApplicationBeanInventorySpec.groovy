package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.FactoryBoot
import com.github.oinsio.gnomish.app.OperatorHomeFixture
import java.nio.file.Path
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
/**
 * The first-party bean inventory of the booted context: every bean whose definition the factory's
 * own code contributes — a scanned or imported first-party class, or a {@code @Bean} method on a
 * first-party configuration — listed by name. A change that adds, drops or renames a bean edits
 * {@link #EXPECTED} in the same diff, so a silent gain or loss fails here instead of passing the
 * "the context boots" check unnoticed.
 *
 * <p>Implements NFR-R2 of collapse-composition-roots: the context gains only the beans that
 * change names in NFR-R2, and it loses none but the ones that requirement sanctions. FR3, FR10 of
 * add-project-registry: {@code projectScope} joins (the clone every project-scoped command works
 * in), {@code takeCommandSeams} joins (take's clock moved into its seams), and {@code
 * runArgumentsParser} leaves (the run drive builds its parser over the clone). FR18 of
 * supervise-daemon-loops-and-embed-dashboard (design D22): {@code containerRuntimeProbe} and
 * {@code sandboxModeSelector} join (the execution-mode decision is built once by the root); FR9,
 * FR10 (design D11, D12): {@code serveDashboard} joins (the page inside serve, holding the tracker
 * wiring only as its board-reader role).
 */
class ApplicationBeanInventorySpec extends Specification {

    @Shared
    @TempDir
    Path operatorHomeDir

    @Shared
    OperatorHomeFixture operatorHome

    @Shared
    ConfigurableApplicationContext context

    private static final String PRODUCTION_ROOT = 'com.github.oinsio.gnomish.'

    private static final List<String> EXPECTED = [
        'adHocTaskSynthesizer',
        'adapterBindingRegistry',
        'attemptPersistence',
        'boardCommand',
        'checkClientRegistry',
        'checkEquipment',
        'checkParamsValidatorRegistry',
        'claimEpochBook',
        'com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration',
        'com.github.oinsio.gnomish.adapter.sandbox.SandboxBindingConfiguration',
        'com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration',
        'containerRuntimeProbe',
        'containerSupports',
        'dashboardCommand',
        'errorConsoleIO',
        'factory-com.github.oinsio.gnomish.FactoryProperties',
        'factory.bindings-com.github.oinsio.gnomish.sandbox.BindingProperties',
        'factory.sandbox-com.github.oinsio.gnomish.sandbox.SandboxProperties',
        'factory.serve-com.github.oinsio.gnomish.ServeProperties',
        'factoryApplication',
        'filesExistCheckRunner',
        'gitProcessRunner',
        'gitVersionCheck',
        'instantSource',
        'manualRunAssembly',
        'manualRunConfiguration',
        'manualRunDrive',
        'manualRunRunner',
        'manualRunners',
        'pipelineSource',
        'pipelineStartup',
        'projectCommand',
        'projectScope',
        'reportCommands',
        'runExitCodeMapper',
        'sandboxLifecyclePass',
        'sandboxModeSelector',
        'secretsProvider',
        'serveAssembly',
        'serveCommand',
        'serveDashboard',
        'serveExitCodeExceptionMapper',
        'serveRuntimeAssembly',
        'shellCommandCheckRunner',
        'slotWiringFactory',
        'statusCommand',
        'subcommandDispatch',
        'systemConsoleIO',
        'takeCommand',
        'takeCommandSeams',
        'takeExitCodeExceptionMapper',
        'taskGit',
        'taskIdRandom',
        'timeEquipment',
        'trackerAdapterRegistry',
        'trackerCommandConfiguration',
        'trackerSubsectionValidatorRegistry',
        'trackerWiring',
        'usageCommand',
    ]

    // Design D8 of add-project-registry: booted through the CommandExit argument registration,
    // against a factory home of the spec's own.
    def setupSpec() {
        operatorHome = OperatorHomeFixture.install(operatorHomeDir.resolve('home'))
        context = FactoryBoot.boot()
    }

    def cleanupSpec() {
        context?.close()
        operatorHome?.close()
    }

    // NFR-R2 of collapse-composition-roots: the inventory is exactly the declared list
    def "the context holds exactly the declared first-party beans"() {
        expect:
        firstPartyBeanNames() == EXPECTED.toSorted()
    }

    private List<String> firstPartyBeanNames() {
        ConfigurableListableBeanFactory factory = context.beanFactory
        factory.beanDefinitionNames.findAll { name ->
            def definition = factory.getBeanDefinition(name)
            def origin = definition.beanClassName
                    ?: definition.factoryBeanName?.with {
                        factory.getType(it)?.name
                    }
            origin?.startsWith(PRODUCTION_ROOT)
        }.toSorted()
    }
}
