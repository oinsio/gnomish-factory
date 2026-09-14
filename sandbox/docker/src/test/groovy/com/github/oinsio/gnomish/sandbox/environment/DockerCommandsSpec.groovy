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
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')
    static final DeclaredVolumeOverrides NO_OVERRIDES = new DeclaredVolumeOverrides([])

    private static ContainerRunSpec runSpec(
            Map opts = [:]) {
        new ContainerRunSpec(
                opts.get('key', KEY) as String,
                opts.get('image', 'gnomish/img:1') as String,
                opts.get('runtime', 'sysbox-runc') as String,
                opts.get('limits', LIMITS) as ResourceLimits,
                opts.get('enforceDiskQuota', false) as boolean,
                opts.get('workingCopy', '/gnomish/work') as String,
                opts.get('ownership', OWNERSHIP) as ObjectOwnership,
                opts.get('overrides', NO_OVERRIDES) as DeclaredVolumeOverrides)
    }

    def "FR3: createNetwork is internal-only and carries all four ownership labels"() {
        when:
        def argv = DockerCommands.createNetwork(KEY, OWNERSHIP)

        then:
        argv == [
            'network',
            'create',
            '--internal',
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            '--label',
            'com.github.oinsio.gnomish.mode=tracked',
            '--label',
            'com.github.oinsio.gnomish.project=proj-1',
            'gnomish-net-' + KEY
        ]
    }

    def "FR3: createVolume carries all four ownership labels"() {
        when:
        def argv = DockerCommands.createVolume(KEY, OWNERSHIP)

        then:
        argv == [
            'volume',
            'create',
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            '--label',
            'com.github.oinsio.gnomish.mode=tracked',
            '--label',
            'com.github.oinsio.gnomish.project=proj-1',
            'gnomish-vol-' + KEY
        ]
    }

    def "FR6: startContainer starts a stopped task container by name"() {
        expect:
        DockerCommands.startContainer('gnomish-box-' + KEY) == ['start', 'gnomish-box-' + KEY]
    }

    def "FR10: runContainer applies runtime, cpu/memory/pid limits, the labelled mount and workdir"() {
        when:
        def argv = DockerCommands.runContainer(runSpec())

        then: 'the run is detached, named, labelled, on the internal network'
        argv.subList(0, 16) == [
            'run',
            '-d',
            '--name',
            'gnomish-box-' + KEY,
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            '--label',
            'com.github.oinsio.gnomish.mode=tracked',
            '--label',
            'com.github.oinsio.gnomish.project=proj-1',
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
        def argv = DockerCommands.runContainer(runSpec(image: 'img', runtime: 'runc', enforceDiskQuota: true))

        then:
        def i = argv.indexOf('--storage-opt')
        i >= 0
        argv[i + 1] == 'size=20g'
    }

    // FR2, FR3 of fix-image-declared-volumes: the override fragments occupy the image's declared
    // paths, and they come after the factory's own working-copy mount — the order that makes an
    // image declaring the working copy keep the factory volume rather than a tmpfs over it.
    def "FR3: the declared-volume overrides are appended after the working-copy mount"() {
        when:
        def argv = DockerCommands.runContainer(
                runSpec(overrides: new DeclaredVolumeOverrides(['/cache', '/var/lib/tool'])))

        then: 'both fragments are present, in the image\'s declaration order'
        argv.indexOf('type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777')
                <argv.indexOf('type=tmpfs,destination=/var/lib/tool,tmpfs-size=64m,tmpfs-mode=1777')

        and: 'after the working copy mount and workdir, and before the image'
        argv.indexOf('-w') <argv.indexOf('type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777')
        argv.indexOf('type=tmpfs,destination=/var/lib/tool,tmpfs-size=64m,tmpfs-mode=1777') <argv.indexOf('gnomish/img:1')

        and: 'each fragment is introduced by its own --mount'
        argv[argv.indexOf('type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777') - 1] == '--mount'
        argv[argv.indexOf('type=tmpfs,destination=/var/lib/tool,tmpfs-size=64m,tmpfs-mode=1777') - 1] == '--mount'

        and: 'the keepalive tail is unchanged'
        argv[-3..-1] == [
            'gnomish/img:1',
            'sleep',
            '2147483647'
        ]
    }

    def "FR3: an image declaring nothing the factory does not mount adds no fragment"() {
        expect:
        !DockerCommands.runContainer(runSpec()).contains('--mount')
    }

    // NFR-S1 of fix-image-declared-volumes: the override introduces no host path and no named
    // object — the only host-backed or named mount in the argv stays the factory's own volume.
    def "NFR-S1: every override fragment is memory-backed, with no source and no volume name"() {
        when:
        def argv = DockerCommands.runContainer(
                runSpec(overrides: new DeclaredVolumeOverrides(['/cache', '/var/lib/tool'])))
        def fragments = argv.indices.findAll {
            argv[it] == '--mount'
        }.collect {
            argv[it + 1]
        }

        then:
        fragments.size() == 2
        fragments.every { it.startsWith('type=tmpfs,') }
        fragments.every { !it.contains('source=') && !it.contains('src=') }
        fragments.every { !it.contains('gnomish-vol-') }

        and: 'each carries the bound and the mode — the runtime mounts a tmpfs root-owned, so the'
        // mode is what keeps the image's own non-root user able to write a path it owned before
        // the override, where the anonymous volume this replaces copied the owner too (D5).
        fragments.every {
            it.contains('tmpfs-size=64m') && it.contains('tmpfs-mode=1777')
        }

        and: 'exactly one -v, the factory\'s own working-copy volume'
        argv.count { it == '-v' } == 1
        argv[argv.indexOf('-v') + 1] == 'gnomish-vol-' + KEY + ':/gnomish/work'
    }

    // FR4, design D6 of fix-image-declared-volumes: the one exemption from the mechanism. The
    // seed helper is self-removing, and docker removes a --rm container's anonymous volumes with
    // it — so this pin is what says the exemption rests on --rm and not on an oversight.
    def "FR4: the seed helper stays exempt by being self-removing"() {
        expect:
        DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null, OWNERSHIP)
                .subList(0, 2) == ['run', '--rm']
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

    // FR1 of fix-image-declared-volumes: the template is one argv element and carries no shell
    // quoting — quotes would become part of the Go template rather than delimit it.
    def "FR1: inspectImageVolumes asks the runtime for the image's declared volume paths"() {
        expect:
        DockerCommands.inspectImageVolumes('gnomish/img:1') == [
            'image',
            'inspect',
            '-f',
            '{{json .Config.Volumes}}',
            'gnomish/img:1'
        ]
    }

    def "FR11: stop keeps volume and network; inspect reads runtime state"() {
        expect:
        DockerCommands.stop('n') == ['stop', 'n']
        DockerCommands.inspectContainerState('n') == [
            'inspect',
            '-f',
            '{{.State.Running}} {{.State.FinishedAt}} {{.State.OOMKilled}}',
            'n'
        ]
    }

    // FR1 of polish-sandbox-forensics: the OOM flag is what tells an exit 137 killed by the
    // container's memory limit apart from an ordinary forced terminate.
    def "FR1: the state line's OOMKilled field reads back, and an unreadable line claims nothing"() {
        expect:
        DockerCommands.oomKilled(line) == oom

        where:
        line || oom
        'false 2026-08-07T10:00:00Z true' || true
        'false 2026-08-07T10:00:00Z true\n' || true
        '  false 2026-08-07T10:00:00Z true  ' || true
        'false 2026-08-07T10:00:00Z false' || false
        'false 2026-08-07T10:00:00Z TRUE' || false
        'false 2026-08-07T10:00:00Z' || false
        'true' || false
        '' || false
    }

    def "FR11: remove commands use the derived names"() {
        expect:
        DockerCommands.removeContainer('n') == ['rm', '-f', 'n']
        DockerCommands.removeVolume('n') == ['volume', 'rm', 'n']
        DockerCommands.removeNetwork('n') == ['network', 'rm', 'n']
    }
}
