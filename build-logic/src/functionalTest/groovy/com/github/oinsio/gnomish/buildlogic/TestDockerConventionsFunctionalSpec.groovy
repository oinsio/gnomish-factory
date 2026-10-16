package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@code TestDockerEnvironment} reached the way every module reaches it, through
 * {@code test-conventions} (FR13, NFR-C3, UX4, design D14 of fix-terminal-receipt-convergence):
 * a miniature module applies the real {@code library-conventions} over the repository's own
 * catalog and {@code build-checks}, as {@code TestEnvironmentHygieneFunctionalSpec} does, and the
 * verdict is given by the forked test JVM itself — {@code FooSpec} reads its own environment and
 * the configuration its {@code DOCKER_CONFIG} names, so an operator configuration that leaks
 * fails the {@code test} task, and fails PIT's coverage pass in a minion.
 *
 * <p>The launching process holds an operator configuration that names a credential helper, a
 * proxy and plugin hooks, an operator context, and a {@code DOCKER_HOST} no daemon answers on: with
 * it set no {@code docker} process runs, so the inner build stays Docker-free on any machine. The
 * branches of the composition itself are {@link TestDockerEnvironmentFunctionalSpec}'s.
 */
class TestDockerConventionsFunctionalSpec extends Specification {

    static final String OPERATOR_CONFIG = '''\
{"credsStore": "desktop", "credHelpers": {"registry.invalid": "desktop"},
 "proxies": {"default": {"httpProxy": "http://proxy.invalid:3128"}},
 "plugins": {"debug": {"hooks": "exec"}}, "currentContext": "operator-context"}
'''

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home)
        repo.write('settings.gradle', """\
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))}'))
        }
    }
}
buildCache {
    local {
        directory = '${GradleRunnerSupport.quotedPath(home.resolve('build-cache').toString())}'
    }
}
rootProject.name = 'mini-docker-conventions'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
include 'mod-a'
""")
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.write('mod-a/build.gradle', "plugins {\n    id 'library-conventions'\n}\n")
        repo.writeTestConventionInputs()
        repo.write('mod-a/src/main/java/com/github/oinsio/gnomish/mini/Foo.java', '''\
package com.github.oinsio.gnomish.mini;

public final class Foo {
    private Foo() {}

    public static int twice(int x) { return x * 2; }
}
''')
        repo.write('mod-a/src/test/groovy/com/github/oinsio/gnomish/mini/FooSpec.groovy', '''\
package com.github.oinsio.gnomish.mini

import spock.lang.Specification

class FooSpec extends Specification {
    def "runs under the build's own Docker client configuration and the operator's endpoint"() {
        given:
        String dir = System.getenv('DOCKER_CONFIG')
        String config = new File(dir, 'config.json').text

        expect:
        dir != System.getenv('MINI_OPERATOR_DOCKER_CONFIG')
        ['credsStore', 'credHelpers', 'proxies', 'plugins', 'hooks', 'currentContext'].every { !config.contains('"' + it + '"') }
        System.getenv('DOCKER_CONTEXT') == null
        System.getenv('DOCKER_HOST') == System.getenv('MINI_OPERATOR_DOCKER_HOST')
        Foo.twice(3) == 6
    }
}
''')
        repo.init([:])
    }

    // Scenario: build-system/quality-gates / The operator's credential helper is not consulted
    // Scenario: build-system/quality-gates / Every module gets the Docker configuration from its test conventions
    def "FR13, UX4: a module's test JVM reads the build's configuration, not the operator's with its credential helper"() {
        when:
        BuildResult result = operatorRunner('unix:///nonexistent/a.sock', operatorDir('operator'), ':mod-a:test').build()

        then: 'FooSpec passed in the forked JVM'
        result.task(':mod-a:test').outcome == SUCCESS
    }

    // Scenario: build-system/quality-gates / PIT minions run under the same configuration
    def "FR13: PIT's minions read the build's configuration too"() {
        when:
        BuildResult result = operatorRunner('unix:///nonexistent/a.sock', operatorDir('operator'),
                ':mod-a:pitestVerifyAllKilled', '-PpitScope=com.github.oinsio.gnomish.mini.Foo').build()

        then: 'the coverage pass ran FooSpec in a minion and it passed'
        result.task(':mod-a:pitest').outcome == SUCCESS
        result.task(':mod-a:pitestVerifyAllKilled').outcome == SUCCESS
    }

    // Scenario: build-system/quality-gates / Machine-specific values do not split the cache
    def "NFR-C3: two machines that differ only in endpoint and plugin directory share a test task's cache entry"() {
        given: 'the first machine stores the result'
        operatorRunner('unix:///nonexistent/a.sock', operatorDir('first'), ':mod-a:test', '--build-cache').build()
        projectDir.resolve('mod-a/build').deleteDir()

        and: 'the second machine has another endpoint and a plugin directory'
        Path second = operatorDir('second')
        Files.createDirectories(second.resolve('cli-plugins'))

        when:
        BuildResult result = operatorRunner('tcp://other.invalid:2375', second, ':mod-a:test', '--build-cache').build()

        then:
        result.task(':mod-a:test').outcome == FROM_CACHE
    }

    /** A build launched by an operator with this endpoint, this configuration directory and a context of their own. */
    private GradleRunner operatorRunner(String endpoint, Path operatorConfig, String... tasks) {
        GradleRunner runner = repo.runner(tasks)
        Map<String, String> environment = new HashMap<>(runner.environment)
        environment.remove('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
        environment.putAll([
            DOCKER_HOST                : endpoint,
            DOCKER_CONFIG              : operatorConfig.toString(),
            DOCKER_CONTEXT             : 'operator-context',
            MINI_OPERATOR_DOCKER_HOST  : endpoint,
            MINI_OPERATOR_DOCKER_CONFIG: operatorConfig.toString(),
        ])
        runner.withEnvironment(environment)
    }

    private Path operatorDir(String name) {
        Path dir = Files.createDirectories(home.resolve(name))
        dir.resolve('config.json').text = OPERATOR_CONFIG
        dir
    }
}
