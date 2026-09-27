package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.FactoryApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Specification

/**
 * The first-party bean inventory of the booted context: every bean whose definition the factory's
 * own code contributes — a scanned or imported first-party class, or a {@code @Bean} method on a
 * first-party configuration — listed by name. A change that adds, drops or renames a bean edits
 * {@link #EXPECTED} in the same diff, so a silent gain or loss fails here instead of passing the
 * "the context boots" check unnoticed.
 *
 * <p>Implements NFR-R2 of collapse-composition-roots: the context gains only the beans that
 * change names in NFR-R2, and it loses none but the ones that requirement sanctions.
 */
@SpringBootTest(classes = FactoryApplication)
class ApplicationBeanInventorySpec extends Specification {

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
        'containerSupports',
        'dashboardCommand',
        'errorConsoleIO',
        'factory-com.github.oinsio.gnomish.FactoryProperties',
        'factory.bindings-com.github.oinsio.gnomish.sandbox.BindingProperties',
        'factory.sandbox-com.github.oinsio.gnomish.sandbox.SandboxProperties',
        'factory.serve-com.github.oinsio.gnomish.ServeProperties',
        'factoryApplication',
        'factoryPaths',
        'filesExistCheckRunner',
        'gitProcessRunner',
        'gitVersionCheck',
        'javaTimeClock',
        'manualRunAssembly',
        'manualRunConfiguration',
        'manualRunDrive',
        'manualRunRunner',
        'manualRunners',
        'pipelineSource',
        'pipelineStartup',
        'reportCommands',
        'runArgumentsParser',
        'runExitCodeMapper',
        'sandboxLifecyclePass',
        'secretsProvider',
        'serveAssembly',
        'serveCommand',
        'serveExitCodeExceptionMapper',
        'serveRuntimeAssembly',
        'shellCommandCheckRunner',
        'slotWiringFactory',
        'statusCommand',
        'subcommandDispatch',
        'systemClock',
        'systemConsoleIO',
        'takeCommand',
        'takeExitCodeExceptionMapper',
        'taskGit',
        'taskIdRandom',
        'threadSleeper',
        'trackerAdapterRegistry',
        'trackerCommandConfiguration',
        'trackerSubsectionValidatorRegistry',
        'trackerWiring',
        'usageCommand',
    ]

    @Autowired
    ConfigurableApplicationContext context

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
