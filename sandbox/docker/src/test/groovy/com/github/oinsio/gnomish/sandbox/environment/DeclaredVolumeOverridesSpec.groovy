package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR1, FR2, FR3, NFR-R2 of fix-image-declared-volumes: the one owner of the
 * image-declared-volume override set takes the image's declared paths from its {@link ImageFacts}
 * (design D3 of add-sandbox-base-image), subtracts the destinations the container mounts
 * explicitly, and renders bounded {@code tmpfs} fragments in the image's own order. The refusal
 * of an answer it cannot trust moved with the read to {@code ImageFactsSpec}. No daemon.
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

    def "FR1, FR2: #answer yields #expected once the explicit destinations are subtracted"() {
        expect:
        DeclaredVolumeOverrides.resolve(facts(answer), explicit as Set).paths() == expected

        where:
        answer | explicit || expected
        'null' | [] || []
        '{}' | [] || []
        '{"/cache":{}}' | [] || ['/cache']
        '{"/cache":{},"/var/lib/tool":{}}' | [] || ['/cache', '/var/lib/tool']
        '{"/cache":{},"/gnomish/work":{}}' | [WORKING_COPY] || ['/cache']
        '{"/gnomish/work":{}}' | [WORKING_COPY] || []
    }

    // Scenario: sandbox/execution-environment / The override is a pure function of image and explicit mounts
    def "NFR-R2: two resolutions over the same answer and destinations are equal and render the same argv"() {
        given:
        def answer = '{"/var/lib/tool":{},"/cache":{},"/gnomish/work":{}}'

        when:
        def first = DeclaredVolumeOverrides.resolve(facts(answer), [WORKING_COPY] as Set)
        def second = DeclaredVolumeOverrides.resolve(facts(answer), [WORKING_COPY] as Set)

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

    /**
     * The facts of an image whose inspect answered {@code volumes} as its declared volumes — the
     * JSON this spec fed the fake runner before {@code ImageFacts} took the read over (design D3
     * of add-sandbox-base-image), now one step up, through the owner's own parse.
     */
    private static ImageFacts facts(String volumes) {
        ImageFacts.parse(IMAGE, UntrustedText.subprocess('{"Volumes":' + volumes + '}'))
    }
}
