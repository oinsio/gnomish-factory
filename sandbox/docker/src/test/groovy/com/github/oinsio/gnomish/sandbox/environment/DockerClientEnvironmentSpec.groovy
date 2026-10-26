package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.StdinSource
import spock.lang.Specification

/**
 * FR4, NFR-S2 of fix-docker-exec-env-argv (design D3): the docker client's environment for one
 * streaming exec is subtractive — a copy of the inherited environment, minus the launch's withheld
 * credential names, plus the launch's composed environment, in that order. The function is pure:
 * it neither mutates nor aliases the inherited map.
 */
class DockerClientEnvironmentSpec extends Specification {

    static final String KEY = 'org-repo-42'
    static final String WORKDIR = '/gnomish/work'
    static final String CREDENTIAL = 'GNOMISH_GITHUB_TOKEN'
    static final String PASSTHROUGH = 'MY_TOOL_HOME'
    static final String DAEMON = 'DOCKER_HOST'

    private static DockerExecLaunch launch(Map<String, String> env, Set<String> withheld) {
        new ContainerExecLaunches(KEY, WORKDIR, withheld)
                .launch(new LinkedHashMap<String, String>(env), StdinSource.NONE, ['claude', '-p'])
    }

    private static Map<String, String> inherited() {
        new LinkedHashMap<String, String>([
            (CREDENTIAL) : 'ghp-inherited-secret',
            (PASSTHROUGH): '/stale/value',
            (DAEMON) : 'unix:///var/run/docker.sock',
        ])
    }

    // FR4, NFR-S2: the credential is removed, the passthrough takes the live value, the unrelated
    //     daemon address is kept.
    def "FR4: compose drops the withheld credential, lays the live passthrough value, keeps DOCKER_HOST"() {
        given:
        def input = inherited()
        def launch = launch([(PASSTHROUGH): '/live/value'], [CREDENTIAL] as Set)

        when:
        def composed = DockerClientEnvironment.compose(input, launch)

        then:
        !composed.containsKey(CREDENTIAL)
        composed[PASSTHROUGH] == '/live/value'
        composed[DAEMON] == 'unix:///var/run/docker.sock'
        composed.keySet() == [PASSTHROUGH, DAEMON] as Set
    }

    // NFR-S2: an environment-free exec gets the inherited environment minus declared credentials,
    //     with nothing laid on top.
    def "NFR-S2: an empty launch removes the withheld names and nothing else"() {
        given:
        def input = inherited()
        def launch = launch([:], [CREDENTIAL] as Set)

        when:
        def composed = DockerClientEnvironment.compose(input, launch)

        then:
        composed == [(PASSTHROUGH): '/stale/value', (DAEMON): 'unix:///var/run/docker.sock']
    }

    // FR4: compose is pure — the inherited map is neither mutated nor returned as the result.
    def "FR4: compose neither mutates nor aliases the inherited map"() {
        given:
        def input = inherited()
        def before = new LinkedHashMap<String, String>(input)
        def launch = launch([(PASSTHROUGH): '/live/value'], [CREDENTIAL] as Set)

        when:
        def composed = DockerClientEnvironment.compose(input, launch)

        then:
        input == before
        !composed.is(input)
    }

    // FR4: a withheld name the factory process never exported is no error.
    def "FR4: a withheld name absent from the inherited environment is ignored"() {
        given:
        def input = new LinkedHashMap<String, String>([(DAEMON): 'tcp://daemon:2375'])
        def launch = launch([:], [CREDENTIAL, 'SONAR_TOKEN'] as Set)

        when:
        def composed = DockerClientEnvironment.compose(input, launch)

        then:
        composed == [(DAEMON): 'tcp://daemon:2375']
    }

    // FR4 (design D3: copy, remove withheld, then put all of the launch's environment): a composed
    //     entry the run deliberately forwards wins over a withheld name of the same spelling, since
    //     the subtraction applies to the inherited copy before the composed map is laid on top.
    def "FR4: a composed entry whose name is also withheld carries the composed value"() {
        given:
        def input = inherited()
        def launch = launch([(CREDENTIAL): 'composed-value'], [CREDENTIAL] as Set)

        when:
        def composed = DockerClientEnvironment.compose(input, launch)

        then:
        composed[CREDENTIAL] == 'composed-value'
        composed[PASSTHROUGH] == '/stale/value'
        composed[DAEMON] == 'unix:///var/run/docker.sock'
    }
}
