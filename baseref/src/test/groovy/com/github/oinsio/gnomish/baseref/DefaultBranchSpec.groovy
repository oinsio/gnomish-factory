package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * FR4, FR10, M2, NFR-S3 of add-base-ref-resolution: the repository default branch is a type, not a
 * string, so the one stand-in that would silently change the default-branch tier's meaning cannot
 * be constructed at all — the enforcement the hand-maintained file list of {@code
 * BaseHeadDefaultBoundarySpec} used to stand in for on the trusted-tier half of the path.
 */
class DefaultBranchSpec extends Specification {

    // FR4, FR10: a well-formed branch name is carried as it was reported, refs/heads/ already off.
    def "a name the remote reported is carried verbatim"() {
        expect:
        new DefaultBranch(name).name() == name

        where:
        name << [
            'main',
            'develop',
            'release/1.19',
            'v1.2.3'
        ]
    }

    // FR4, FR10, M2: the whole point of the type — the stand-in is unconstructible, so no caller
    //     can hand the autonomous resolver the factory clone's checkout under the remote's name.
    def "HEAD is refused: it is the stand-in the tier exists to keep out"() {
        when:
        new DefaultBranch(BaseRefResolver.LOCAL_HEAD_REF)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("invalid default branch 'HEAD'")
        e.message.contains('git itself refuses it as a branch name')

        and: 'the same answer as a violation, for a report rather than a throw'
        DefaultBranch.violation(BaseRefResolver.LOCAL_HEAD_REF).present
    }

    // NFR-S3: every other rule is the ref-name grammar's, so nothing reaches a refspec unchecked.
    def "a name the ref-name grammar refuses is refused with the grammar's own reason"() {
        when:
        new DefaultBranch(name)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(reason)

        and:
        DefaultBranch.violation(name).get() == reason

        where:
        name || reason
        '' || 'must not be blank'
        '--upload-pack=evil' || "must not start with '-'"
        'release/../../secret' || "must not contain '..' or '@{'"
        'feature/*' || "must name one ref, not a pattern: '*' is not allowed here"
    }

    // FR4: a well-formed name has nothing to report, which is what lets the adapter carry it on.
    def "a well-formed name has no violation"() {
        expect:
        DefaultBranch.violation('main').empty
    }

    def "a missing name is refused outright"() {
        when:
        DefaultBranch.violation(null)

        then:
        thrown(NullPointerException)
    }

    // The value is a record: two reports of the same branch are one value, so a discovery outcome
    //     compares by what origin said rather than by identity.
    def "equal names are one value"() {
        expect:
        new DefaultBranch('main') == new DefaultBranch('main')
        new DefaultBranch('main') != new DefaultBranch('develop')
        new DefaultBranch('main').hashCode() == new DefaultBranch('main').hashCode()
        new DefaultBranch('main').toString().contains('main')
    }
}
