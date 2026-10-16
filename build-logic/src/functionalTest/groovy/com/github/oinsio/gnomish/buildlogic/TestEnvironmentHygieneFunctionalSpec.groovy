package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code TestEnvironmentHygiene} (FR22, M10, design D14 of
 * make-checkpoint-gate-durable): a build launched with {@code GNOMISH_DECISION_FILE} in its
 * environment — as a build run from inside a gnome's round is — forks test JVMs and PIT minions
 * that do not see it, while a {@code GNOMISH_*} variable the build sets itself still arrives, and
 * so do the machine's tooling variables of the launching process (scenario "The machine's tooling
 * variables survive").
 *
 * <p>The miniature module applies the real {@code library-conventions} (hence the real {@code
 * test-conventions}) over the repository's own catalog and {@code build-checks}, as {@code
 * MutationScopeConventionsFunctionalSpec} does, and the verdict is given by the forked JVM itself:
 * {@code FooSpec} asserts on its own {@code System.getenv()}, so a leaked variable fails the
 * {@code test} task, and fails PIT's coverage pass ("tests did not pass without mutation") in a
 * minion.
 */
class TestEnvironmentHygieneFunctionalSpec extends Specification {

    static final String DECISION_FILE = 'GNOMISH_DECISION_FILE'
    static final String BUILD_OWNED = 'GNOMISH_BUILD_OWNED'
    static final String RYUK_DISABLED = 'TESTCONTAINERS_RYUK_DISABLED'
    static final String DOCKER_HOST = 'DOCKER_HOST'
    /** An endpoint no daemon answers on: the inner build only passes it through. */
    static final String OPERATOR_ENDPOINT = 'unix:///nonexistent/stand-in/docker.sock'

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
rootProject.name = 'mini-env-hygiene'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
include 'mod-a'
""")
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.write('mod-a/build.gradle', """\
plugins {
    id 'library-conventions'
}
// A GNOMISH_* variable the build sets itself: the hygiene must leave it alone.
tasks.named('test') { environment '${BUILD_OWNED}', 'kept' }
tasks.named('pitest') { environment '${BUILD_OWNED}', 'kept' }
""")
        repo.writeTestConventionInputs()
        repo.write('mod-a/src/main/java/com/github/oinsio/gnomish/mini/Foo.java', '''\
package com.github.oinsio.gnomish.mini;

public final class Foo {
    private Foo() {}

    public static int twice(int x) { return x * 2; }
}
''')
        repo.write('mod-a/src/test/groovy/com/github/oinsio/gnomish/mini/FooSpec.groovy', """\
package com.github.oinsio.gnomish.mini

import spock.lang.Specification

class FooSpec extends Specification {
    def "runs in a JVM that inherited no GNOMISH_ variable and kept the machine's tooling variables"() {
        expect:
        System.getenv('${DECISION_FILE}') == null
        System.getenv('${BUILD_OWNED}') == 'kept'
        System.getenv('${RYUK_DISABLED}') == 'true'
        System.getenv('${DOCKER_HOST}') == '${OPERATOR_ENDPOINT}'
        Foo.twice(3) == 6
    }
}
""")
        repo.init([:])
    }

    // Scenario: build-system/quality-gates / A variable of the launching process does not reach the test JVM
    // Scenario: build-system/quality-gates / The machine's tooling variables survive
    // Scenario: build-system/quality-gates / Only the factory's own namespace is removed
    // Scenario: build-system/quality-gates / A variable the build sets itself survives
    // Scenario: build-system/quality-gates / Every module gets the strip from its test conventions
    def "FR22, M10: a build run with GNOMISH_DECISION_FILE in its environment forks a test JVM that does not see it"() {
        when:
        BuildResult result = withOperatorVariables(repo.runner(':mod-a:test')).build()

        then: 'FooSpec passed in the forked JVM: no decision file, the build-owned variable kept'
        result.task(':mod-a:test').outcome == SUCCESS
    }

    /**
     * Pins the Gradle behavior the configuration-time strip rests on (see {@code
     * TestEnvironmentHygiene}): a reuse forks with the stored map, not the launching environment.
     * Should a Gradle upgrade start re-reading the inherited environment on reuse, this goes red
     * and the strip must move to execution time.
     */
    def "FR22: a configuration-cache reuse does not forward a variable present only at reuse"() {
        given: 'the configuration is stored by a build whose environment has no GNOMISH_ variable'
        withoutOperatorVariables(repo.runner(':mod-a:test', '--rerun', '--configuration-cache')).build()

        when: 'a gnome launches the same build, reusing that configuration'
        BuildResult reused = withOperatorVariables(repo.runner(':mod-a:test', '--rerun', '--configuration-cache')).build()

        then:
        reused.output.contains('Reusing configuration cache.')
        reused.task(':mod-a:test').outcome == SUCCESS
    }

    // Scenario: build-system/quality-gates / PIT minions are stripped the same way
    def "FR22: PIT's minions inherit no GNOMISH_ variable from the build"() {
        when:
        BuildResult result = withOperatorVariables(
                repo.runner(':mod-a:pitestVerifyAllKilled', '-PpitScope=com.github.oinsio.gnomish.mini.Foo')).build()

        then: 'the coverage pass ran FooSpec in a minion and it passed'
        result.task(':mod-a:pitest').outcome == SUCCESS
        result.task(':mod-a:pitestVerifyAllKilled').outcome == SUCCESS
    }

    /** A build launched outside any round — this suite may itself run inside one, so the inherited keys go. */
    private GradleRunner withoutOperatorVariables(GradleRunner runner) {
        runner.withEnvironment(runner.environment.findAll { !it.key.startsWith('GNOMISH_') } + toolingVariables())
    }

    /** The environment of a build launched from inside a gnome's round, plus an inherited copy of the build-owned key. */
    private GradleRunner withOperatorVariables(GradleRunner runner) {
        File decisionFile = home.resolve('decision.json').toFile()
        runner.withEnvironment(runner.environment + toolingVariables()
                + [(DECISION_FILE): decisionFile.absolutePath, (BUILD_OWNED): 'inherited'])
    }

    /**
     * The machine's tooling variables the strip must leave alone. {@code DOCKER_HOST} names a socket
     * no daemon listens on: the inner build only hands it on, and with it set no {@code docker}
     * process runs to resolve an endpoint, so the inner build stays Docker-free.
     * {@code DOCKER_CONFIG} is not one of them any more: the build replaces it with its own (design
     * D14 of fix-terminal-receipt-convergence).
     */
    private Map<String, String> toolingVariables() {
        [(RYUK_DISABLED): 'true', (DOCKER_HOST): OPERATOR_ENDPOINT]
    }
}
