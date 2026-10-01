package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.ConfigurationViolationsException
import com.github.oinsio.gnomish.app.OperatorHomeFixture
import java.nio.file.Path
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A {@code FACTORY_*} environment variable is refused at startup (FR7 of add-project-registry,
 * scenario "Environment variable is refused with the equivalent line"). This spec once asserted
 * the opposite — that {@code FACTORY_INSTANCE_NAME} overrode the bundled value through Spring's
 * relaxed binding (FR3 of add-project-skeleton); the configuration levels close that source, since
 * any variable in the operator's shell could otherwise widen the sandbox.
 *
 * <p>A real OS environment variable cannot be set from inside the running JVM, so the spec hands
 * the application an environment whose {@code systemEnvironment} source carries the variable — the
 * source Spring builds from the OS environment, under the name it gives it. The boot goes through
 * {@link FactoryBoot}, as the packaged jar's does.
 *
 * <p>Implements FR7, NFR-S1 of add-project-registry.
 */
class FactoryEnvironmentOverrideSpec extends Specification {

    @TempDir
    Path tmp

    OperatorHomeFixture operatorHome

    def setup() {
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
    }

    def cleanup() {
        operatorHome.close()
    }

    // FR7: "Environment variable is refused with the equivalent line"
    def "FACTORY_INSTANCE_NAME stops startup and names the equivalent file line"() {
        given:
        def environment = new StandardEnvironment()
        environment.propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        [FACTORY_INSTANCE_NAME: 'from-environment'] as Map<String, Object>))
        def application = FactoryBoot.application()
        application.setEnvironment(environment)
        def home = operatorHome.home

        when:
        application.run()

        then:
        def refused = thrown(ConfigurationViolationsException)
        refused.exitCode == 2
        refused.violations() == [
            'environment variable FACTORY_INSTANCE_NAME is not allowed — found in the environment' +
            ' — factory.* settings are not read from environment variables' +
            " — set 'factory.instance-name: from-environment' in" +
            " ${home.projects().resolve('<name>').resolve('project.yaml')}" +
            ' (no project is registered yet: gnomish project add <name> --dir=<clone>)' +
            " or ${home.hostConfig()}, or pass --factory.instance-name=from-environment"
        ]*.toString()
    }
}
