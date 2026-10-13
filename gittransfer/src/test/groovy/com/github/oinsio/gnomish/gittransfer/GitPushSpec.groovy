package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the owner builds the factory's two pushes to
 * origin. The closing push moves the task branch and the factory register in one atomic push; the
 * mid-round push moves the branch alone. One feature per push pins the whole value, so a flag that
 * appears, disappears or moves is a red spec.
 */
class GitPushSpec extends Specification {

    private static final List<String> COMMON = [
        '--no-follow-tags',
        '--no-recurse-submodules',
        '--end-of-options',
        'origin'
    ]

    private static final Map<String, Optional<String>> ORIGIN_ENVIRONMENT = [
        GIT_CONFIG_PARAMETERS: Optional.<String> empty(),
        GIT_CONFIG_COUNT: Optional.<String> empty(),
        GIT_OBJECT_DIRECTORY: Optional.<String> empty(),
        GIT_ALTERNATE_OBJECT_DIRECTORIES: Optional.<String> empty(),
        GIT_WORK_TREE: Optional.<String> empty(),
        GIT_INDEX_FILE: Optional.<String> empty(),
        GIT_ALLOW_PROTOCOL: Optional.of('https:http:ssh:file'),
    ]

    // FR13: one atomic push names both refs, each from the clone's own ref of the same name.
    def "FR13: the closing push is atomic and names the branch and the register in full"() {
        when:
        def push = GitPush.closing('gnomish/T-1', RegisterRef.forTask('T-1'))

        then:
        push.argv() == ['push', '--atomic'] + COMMON + [
            'refs/heads/gnomish/T-1:refs/heads/gnomish/T-1',
            'refs/gnomish-factory/T-1:refs/gnomish-factory/T-1',
        ]

        and: "origin's environment: inherited overrides stripped, the protocol allowlist set"
        push.environment() == ORIGIN_ENVIRONMENT
    }

    // FR13: a mid-round push leaves the register where the last closing push put it.
    def "FR13: the mid-round push names the branch alone and is not atomic"() {
        when:
        def push = GitPush.branchOnly('gnomish/T-1')

        then:
        push.argv() == ['push'] + COMMON + [
            'refs/heads/gnomish/T-1:refs/heads/gnomish/T-1'
        ]
        push.environment() == ORIGIN_ENVIRONMENT
    }

    def "a branch that is blank or carries a refspec separator is refused: '#branch'"() {
        when:
        build(branch)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('no \':\'')

        where:
        [branch, build] << [
            ['', ' ', 'a:b'],
            [
                { String b -> GitPush.closing(b, RegisterRef.forTask('T-1')) },
                { String b -> GitPush.branchOnly(b) }
            ]
        ].combinations()
    }

    def "a missing input is refused outright"() {
        when:
        construct()

        then:
        thrown(NullPointerException)

        where:
        construct << [
            { GitPush.closing(null, RegisterRef.forTask('T-1')) },
            { GitPush.closing('gnomish/T-1', null) },
            { GitPush.branchOnly(null) },
        ]
    }
}
