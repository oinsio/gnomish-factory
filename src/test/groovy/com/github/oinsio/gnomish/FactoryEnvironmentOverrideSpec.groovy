package com.github.oinsio.gnomish

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.test.context.ContextConfiguration
import spock.lang.Specification

/**
 * Environment-variable override of application.yaml values (FR3 scenario
 * "Environment override", design D10). A real OS environment variable cannot
 * be set from inside the running JVM, so this spec models one faithfully with
 * a {@link SystemEnvironmentPropertySource} inserted at the system-environment
 * precedence tier. That exercises exactly the two mechanisms a real env var
 * relies on: relaxed name mapping (FACTORY_INSTANCE_ID -> factory.instance-id,
 * applied by Spring Boot only to system-environment sources, i.e. sources of
 * that type named "systemEnvironment" or "*-systemEnvironment") and property
 * source precedence above config data (application.yaml). True OS-process
 * verification belongs to the fresh-clone/manual layer, not the unit gate.
 * Implements FR3 of add-project-skeleton.
 */
@SpringBootTest(classes = FactoryApplication)
@ContextConfiguration(initializers = EnvironmentVariableStub)
class FactoryEnvironmentOverrideSpec extends Specification {

    @Autowired
    FactoryProperties factoryProperties

    // FR3: environment override — the env-tier value wins over application.yaml
    def "environment variable FACTORY_INSTANCE_ID overrides the application.yaml value"() {
        expect: 'the bound instance id is the environment value, not the yaml one'
        factoryProperties.instanceId() == EnvironmentVariableStub.ENVIRONMENT_INSTANCE_ID
    }
}

/**
 * Injects FACTORY_INSTANCE_ID as a simulated OS environment variable, placed
 * directly below the real systemEnvironment source — the exact precedence tier
 * OS env vars occupy, above all config-data (application.yaml) sources.
 */
class EnvironmentVariableStub implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ENVIRONMENT_INSTANCE_ID = 'gnomish-from-environment'

    @Override
    void initialize(ConfigurableApplicationContext context) {
        context.environment.propertySources.addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                'test-systemEnvironment',
                [FACTORY_INSTANCE_ID: ENVIRONMENT_INSTANCE_ID] as Map<String, Object>))
    }
}
