package com.github.oinsio.gnomish.architecture

import spock.lang.Specification

/**
 * UX2, FR8, FR9 of fix-envelope-medium: the detector is the gate — a seeded violation must be
 * found, and the build failure a maintainer reads must name the file, the banned call, and the
 * owner to route through.
 *
 * <p>{@link EnvelopeMediumBoundarySpec} can never assert this: over the production tree both of
 * its rules find nothing, so the offender line and the failure text are composed on a path no
 * green run takes, and a degradation of either would ship unnoticed. Task 5.1 of the change
 * checked it once by hand, by temporarily adding a {@code Files.readString} to {@code
 * GitTaskStore}; this spec is that check, kept.
 */
class EnvelopeMediumDetectorSpec extends Specification {

    private static final String SEEDED_PATH =
    'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitTaskStore.java'

    // UX2: a read of an envelope file from the working copy is what rule 1 exists to reject, and
    //     the maintainer who added it learns where the line is, which call tripped the gate, and
    //     that the read belongs to GitTaskStore over GitShowTip.
    def "UX2: a seeded worktree read is found and the failure names the file, the call and the owner"() {
        given: 'a source whose second line reads an envelope file from the working copy'
        def seeded = [
            'final class GitTaskStore {',
            '    var json = Files.readString(worktree.resolve(EnvelopePaths.TASK_JSON_PATH));',
            '}',
        ]

        when: 'the rule scans it for the ten filesystem read calls'
        def offenders = EnvelopeMediumRule.hits(SEEDED_PATH, seeded, EnvelopeMediumRule.READ_CALLS)

        then: 'the offending line is reported with its path, its line number and the call'
        offenders == [
            "$SEEDED_PATH:2 — Files.readString(" as String
        ]

        and: 'and the build failure names all three, plus the owner to route through'
        def failure = EnvelopeMediumRule.readViolation(offenders)
        failure.contains(SEEDED_PATH)
        failure.contains(':2')
        failure.contains('Files.readString(')
        failure.contains('GitTaskStore')
        failure.contains('GitShowTip')
    }

    // UX2: rule 2's half of the same promise — a retyped path name names the owner that declares
    //     it, since the fix is always "call EnvelopePaths", never "spell it more carefully".
    def "UX2: a seeded path literal is found and the failure names the file, the literal and the owner"() {
        given: 'a source that retypes one of the envelope path names'
        def seeded = [
            'final class Elsewhere {',
            '    private static final String DIR = ".gnomish-task";',
            '}',
        ]

        when: 'the rule scans it for the three path literals'
        def offenders = EnvelopeMediumRule.hits('domain/src/main/java/Elsewhere.java', seeded, EnvelopeMediumRule.PATH_LITERALS)

        then: 'the offending line is reported with its path, its line number and the literal'
        offenders == [
            'domain/src/main/java/Elsewhere.java:2 — ".gnomish-task'
        ]

        and: 'and the build failure names all three, plus the owner that declares them'
        def failure = EnvelopeMediumRule.pathViolation(offenders)
        failure.contains('domain/src/main/java/Elsewhere.java')
        failure.contains(':2')
        failure.contains('.gnomish-task')
        failure.contains('EnvelopePaths')
    }

    // The scan reads what the compiler sees: a javadoc or trailing-comment mention of a banned
    //     call or path name is not a defect, and a gate that flagged it would be turned off.
    def "a commented mention is not a hit: #shape"() {
        expect:
        EnvelopeMediumRule.hits(SEEDED_PATH, [commented], tokens).isEmpty()

        where:
        shape | commented | tokens
        'javadoc read call' | ' * Never {@code Files.readString(worktree)} here.' | EnvelopeMediumRule.READ_CALLS
        'line-comment read call' | '// was Files.exists(path) before this change' | EnvelopeMediumRule.READ_CALLS
        'javadoc path name' | ' * the envelope lives under {@code ".gnomish-task/"}' | EnvelopeMediumRule.PATH_LITERALS
        'trailing-comment literal' | 'var p = paths.taskJson(); // was "task.json"' | EnvelopeMediumRule.PATH_LITERALS
    }
}
