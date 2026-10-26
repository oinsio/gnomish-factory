package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.StdinSource
import spock.lang.Specification

/**
 * FR3, NFR-S4, NFR-R1 of fix-docker-exec-env-argv (design D2): one producer,
 * {@link ContainerExecLaunches}, builds the exec argv and the environment that completes it from
 * one composed map, as one {@link DockerExecLaunch} value, so the names the argv carries and the
 * keys the client environment delivers are one set by construction. This is the identity spec the
 * design's single-owner table names for that claim.
 */
class DockerExecLaunchIdentitySpec extends Specification {

    static final String KEY = 'org-repo-42'
    static final String WORKDIR = '/gnomish/work'
    static final Set<String> WITHHELD = ['GITHUB_TOKEN', 'SONAR_TOKEN'] as LinkedHashSet

    private static List<String> envFlagOperands(List<String> argv) {
        argv.indices.findAll { argv[it] == '-e' }.collect { argv[it + 1] }
    }

    private static Map<String, String> ordered(Map<String, String> entries) {
        new LinkedHashMap<String, String>(entries)
    }

    // FR3, NFR-S4: the argv names equal the delivered keys, in order, and the withheld set is the
    //     producer's own; NFR-R1: an empty map yields no -e flag and no error.
    // Scenario: sandbox/execution-environment / Names and values cannot diverge
    def "FR3: the argv names equal the delivered environment's keys, in order, for #label"() {
        given:
        def launches = new ContainerExecLaunches(KEY, WORKDIR, WITHHELD)

        when:
        def launch = launches.launch(env, StdinSource.NONE, ['claude', '-p'])

        then:
        envFlagOperands(launch.argv()) == env.keySet() as List
        launch.argv().count { it == '-e' } == env.size()
        launch.environment() == env
        launch.environment().keySet() as List == envFlagOperands(launch.argv())
        launch.withheld() == WITHHELD
        launch.argv().every { !it.contains('=') }

        where:
        label | env
        'an empty map' | ordered([:])
        'one entry' | ordered([JAVA_HOME: '/opt/jdk'])
        'several entries in order' | ordered([ZETA: 'z', ALPHA: 'a', MIDDLE: 'm', BETA: 'b'])
        'a credential-shaped entry' | ordered([ANTHROPIC_AUTH_TOKEN: 'sk-ant-secret-value'])
    }

    // NFR-R1: an exec with nothing to carry stays flag-free and raises no error.
    def "NFR-R1: an empty composed map yields no -e flag at all and no error"() {
        when:
        def launch = new ContainerExecLaunches(KEY, WORKDIR, WITHHELD)
                .launch([:], StdinSource.NONE, ['true'])

        then:
        noExceptionThrown()
        !launch.argv().contains('-e')
        launch.environment().isEmpty()
        launch.withheld() == WITHHELD
    }
}
