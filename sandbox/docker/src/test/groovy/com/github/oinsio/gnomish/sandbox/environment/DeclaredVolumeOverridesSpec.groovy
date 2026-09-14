package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR1, FR2, FR3, NFR-R1, NFR-R2 of fix-image-declared-volumes: the one owner of the
 * image-declared-volume override set reads the image's declared paths through the
 * runtime seam, subtracts the destinations the container mounts explicitly, renders
 * bounded {@code tmpfs} fragments in the image's own order, and refuses — rather than
 * returning an empty set — any answer it cannot trust. No daemon.
 */
class DeclaredVolumeOverridesSpec extends Specification {

    static final String IMAGE = 'gnomish/img:1'
    static final String WORKING_COPY = '/gnomish/work'

    def "FR2: argv renders one bounded tmpfs mount per path, in the image's order"() {
        expect:
        new DeclaredVolumeOverrides(paths).argv() == argv

        where:
        paths || argv
        [] || []
        ['/cache'] || [
            '--mount',
            'type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777'
        ]
        ['/cache', '/var/lib'] || [
            '--mount',
            'type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777',
            '--mount',
            'type=tmpfs,destination=/var/lib,tmpfs-size=64m,tmpfs-mode=1777'
        ]
        ['/var/lib', '/cache'] || [
            '--mount',
            'type=tmpfs,destination=/var/lib,tmpfs-size=64m,tmpfs-mode=1777',
            '--mount',
            'type=tmpfs,destination=/cache,tmpfs-size=64m,tmpfs-mode=1777'
        ]
    }

    def "NFR-O1: describe() names the overridden paths, or states that there were none"() {
        expect:
        new DeclaredVolumeOverrides(paths).describe() == described
        new DeclaredVolumeOverrides(paths).isEmpty() == empty

        where:
        paths || described | empty
        [] || 'none' | true
        ['/cache'] || '[/cache]' | false
        ['/cache', '/var/lib'] || '[/cache, /var/lib]' | false
    }

    def "FR1: resolve inspects the image through the runtime seam, once"() {
        given:
        def docker = answering('{"/cache":{}}')

        when:
        def overrides = DeclaredVolumeOverrides.resolve(docker, IMAGE, [] as Set)

        then:
        docker.runs == [
            [
                'image',
                'inspect',
                '-f',
                '{{json .Config.Volumes}}',
                IMAGE
            ]
        ]
        overrides.paths() == ['/cache']
    }

    def "FR1, FR2: #answer yields #expected once the explicit destinations are subtracted"() {
        expect:
        DeclaredVolumeOverrides.resolve(answering(answer), IMAGE, explicit as Set).paths() == expected

        where:
        answer | explicit || expected
        'null' | [] || []
        '{}' | [] || []
        '{"/cache":{}}' | [] || ['/cache']
        '{"/cache":{},"/var/lib/tool":{}}' | [] || ['/cache', '/var/lib/tool']
        '{"/cache":{},"/gnomish/work":{}}' | [WORKING_COPY] || ['/cache']
        '{"/gnomish/work":{}}' | [WORKING_COPY] || []
    }

    def "NFR-R1: a refused inspect fails the caller and names the image and the runtime's answer"() {
        given:
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args ->
            new DockerResult(1, '', 'No such image: gnomish/img:1\n')
        }

        when:
        DeclaredVolumeOverrides.resolve(docker, IMAGE, [] as Set)

        then:
        def e = thrown(IllegalStateException)
        e.message == 'docker image inspect of gnomish/img:1 failed: No such image: gnomish/img:1'
    }

    def "NFR-R1: an answer of shape #answer is refused, never read as an empty override set"() {
        when:
        DeclaredVolumeOverrides.resolve(answering(answer), IMAGE, [] as Set)

        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith('docker image inspect of ' + IMAGE + ' returned')

        where:
        answer << [
            '',
            'not json at all',
            '[]',
            '"/cache"',
            '7'
        ]
    }

    def "NFR-R2: two resolutions over the same answer and destinations are equal and render the same argv"() {
        given:
        def answer = '{"/var/lib/tool":{},"/cache":{},"/gnomish/work":{}}'

        when:
        def first = DeclaredVolumeOverrides.resolve(answering(answer), IMAGE, [WORKING_COPY] as Set)
        def second = DeclaredVolumeOverrides.resolve(answering(answer), IMAGE, [WORKING_COPY] as Set)

        then: 'equal as values, and rendered in the order the image declared them'
        first == second
        first.argv() == second.argv()
        first.paths() == ['/var/lib/tool', '/cache']
    }

    def "the path list is copied, so a caller cannot mutate a resolved value"() {
        given:
        def paths = ['/cache']

        when:
        def overrides = new DeclaredVolumeOverrides(paths)
        paths << '/var/lib/tool'

        then:
        overrides.paths() == ['/cache']
    }

    private static RecordingDockerCli answering(String stdout) {
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args -> new DockerResult(0, stdout, '') }
        docker
    }
}
