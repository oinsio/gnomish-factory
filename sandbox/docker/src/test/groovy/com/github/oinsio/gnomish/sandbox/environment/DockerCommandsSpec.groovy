package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ResourceLimits
import spock.lang.Specification

/**
 * FR3, FR4, FR10, FR11 of add-sandbox-core: the pure docker argv builders assemble
 * exactly the right flags — internal network, factory + task labels, resource
 * limits, the opt-in disk quota, the volume mount and workdir, the runtime knob,
 * per-command exec env and interactivity — so the container adapter's behaviour
 * is verified without a Docker daemon.
 */
class DockerCommandsSpec extends Specification {

    static final String KEY = 'org-repo-42'
    static final ResourceLimits LIMITS = new ResourceLimits('4', '3g', 256L, '20g')

    def "FR3: createNetwork is internal-only and carries both factory labels"() {
        when:
        def argv = DockerCommands.createNetwork(KEY)

        then:
        argv == [
            'network',
            'create',
            '--internal',
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            'gnomish-net-' + KEY
        ]
    }

    def "FR3: createVolume carries both factory labels"() {
        when:
        def argv = DockerCommands.createVolume(KEY)

        then:
        argv == [
            'volume',
            'create',
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            'gnomish-vol-' + KEY
        ]
    }

    def "FR6: startContainer starts a stopped task container by name"() {
        expect:
        DockerCommands.startContainer('gnomish-box-' + KEY) == ['start', 'gnomish-box-' + KEY]
    }

    def "FR10: runContainer applies runtime, cpu/memory/pid limits, the labelled mount and workdir"() {
        when:
        def argv = DockerCommands.runContainer(KEY, 'gnomish/img:1', 'sysbox-runc', LIMITS, false, '/gnomish/work')

        then: 'the run is detached, named, labelled, on the internal network'
        argv.subList(0, 12) == [
            'run',
            '-d',
            '--name',
            'gnomish-box-' + KEY,
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            '--network',
            'gnomish-net-' + KEY,
            '--runtime',
            'sysbox-runc'
        ]

        and: 'the three portable limits are present with the configured values'
        argv.containsAll([
            '--cpus',
            '4',
            '--memory',
            '3g',
            '--pids-limit',
            '256'
        ])

        and: 'no disk quota when not opted in'
        !argv.contains('--storage-opt')

        and: 'the volume mounts at the working copy, which is the working directory'
        argv[argv.indexOf('-v') + 1] == 'gnomish-vol-' + KEY + ':/gnomish/work'
        argv[argv.indexOf('-w') + 1] == '/gnomish/work'

        and: 'the image is the last flagless entry before the keepalive command'
        argv[-3..-1] == [
            'gnomish/img:1',
            'sleep',
            '2147483647'
        ]
    }

    def "FR10: the disk quota is added only when opted in"() {
        when:
        def argv = DockerCommands.runContainer(KEY, 'img', 'runc', LIMITS, true, '/gnomish/work')

        then:
        def i = argv.indexOf('--storage-opt')
        i >= 0
        argv[i + 1] == 'size=20g'
    }

    def "FR4: exec sets the workdir, per-entry env, and container, then appends the argv"() {
        when:
        def argv = DockerCommands.exec(KEY, '/gnomish/work', [FOO: 'bar', BAZ: 'qux'], false, ['echo', 'hi'])

        then:
        argv.subList(0, 3) == ['exec', '-w', '/gnomish/work']
        argv.containsAll([
            '-e',
            'FOO=bar',
            '-e',
            'BAZ=qux'
        ])
        !argv.contains('-i')
        argv[-3..-1] == [
            'gnomish-box-' + KEY,
            'echo',
            'hi'
        ]
    }

    def "FR24: exec adds -i only when interactive"() {
        expect:
        DockerCommands.exec(KEY, '/w', [:], true, ['cat']).contains('-i')
        !DockerCommands.exec(KEY, '/w', [:], false, ['cat']).contains('-i')
    }

    def "FR11: stop keeps volume and network; inspect reads runtime state"() {
        expect:
        DockerCommands.stop('n') == ['stop', 'n']
        DockerCommands.inspectContainerState('n') == [
            'inspect',
            '-f',
            '{{.State.Running}} {{.State.FinishedAt}}',
            'n'
        ]
    }

    def "FR11: remove and list commands use the derived names and the factory filter"() {
        expect:
        DockerCommands.removeContainer('n') == ['rm', '-f', 'n']
        DockerCommands.removeVolume('n') == ['volume', 'rm', 'n']
        DockerCommands.removeNetwork('n') == ['network', 'rm', 'n']
        DockerCommands.listContainerNames() == [
            'ps',
            '-a',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--format',
            '{{.Names}}'
        ]
        DockerCommands.listVolumeNames() == [
            'volume',
            'ls',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--format',
            '{{.Name}}'
        ]
        DockerCommands.listNetworkNames() == [
            'network',
            'ls',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--format',
            '{{.Name}}'
        ]
    }
}
