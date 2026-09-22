package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, M3 of fix-claim-epoch-fence: the detector is the gate — a seeded claimless assembly must be
 * found and reported by path, or {@link ClaimlessGitBoundarySpec}'s three green rules mean only
 * that nothing matched.
 *
 * <p>Over a clean tree every rule finds nothing, so a scan that stopped matching — a renamed
 * token, a comment-stripping helper that swallowed a line — looks exactly like a codebase that
 * stopped offending. Task 1.3 of the change checked the teeth once, against the violations that
 * existed on the day it was written; every one of them has since been fixed, so the check went
 * with them. This spec is that check, kept, over sources it seeds itself.
 */
class ClaimlessGitDetectorSpec extends Specification {

    @TempDir
    Path dir

    // FR5, M3: an assembly that wires a tenureless git layer is what rule 1 and rule 2 reject —
    //     the shape every end-to-end fixture had while the read-side fence fired in production.
    def "a seeded #shape is detected"() {
        expect:
        ClaimlessGitRule.spells(source(seeded), token)

        where:
        shape | token | seeded
        'claimless layer' | ClaimlessGitRule.CLAIMLESS_SOURCE | 'var git = fixture.build(ClaimEpochSource.NONE, book);'
        'fresh tenure book' | ClaimlessGitRule.FRESH_BOOK | 'var book = new ClaimEpochBook();'
        'bundle through the fixture' | ClaimlessGitRule.BUNDLE_CONSTRUCTION | 'def git = TaskGitFixture.real(dir)'
    }

    // A javadoc that explains the tenureless variant is documentation, not an assembly; a gate
    //     that flagged it would be turned off rather than obeyed.
    def "a commented mention is not detected: #shape"() {
        expect:
        !ClaimlessGitRule.spells(source(commented), ClaimlessGitRule.CLAIMLESS_SOURCE)

        where:
        shape | commented
        'javadoc line' | ' * wired to {@link ClaimEpochSource#NONE} ClaimEpochSource.NONE, so nothing stamps.'
        'line comment' | '// was ClaimEpochSource.NONE before task 5.1'
        'trailing comment' | 'var git = taskGit; // not ClaimEpochSource.NONE any more'
    }

    // The failure list names the offending file and leaves the allowlisted one out — the report a
    //     maintainer reads is the whole output of a gate that only ever says "no".
    def "the offender list names the seeded file and skips the allowlisted one"() {
        given: 'two sources that both wire a claimless layer, one of them exempt'
        def offending = source('var git = build(ClaimEpochSource.NONE);', 'Offender.groovy')
        def exempt = source('var git = build(ClaimEpochSource.NONE);', 'Exempt.groovy')

        when: 'the rule judges them against an allowlist holding only the second'
        def offenders = ClaimlessGitRule.offenders(
                [offending, exempt], ClaimlessGitRule.CLAIMLESS_SOURCE, [relative(exempt)])

        then: 'only the unexempt one is reported, by its path'
        offenders == [relative(offending)]

        and: 'and the exempt one counts as reached, so a row that stops spelling the token fails'
        ClaimlessGitRule.allowlistedSpelling(
                [offending, exempt], [relative(exempt)], ClaimlessGitRule.CLAIMLESS_SOURCE) ==
                [relative(exempt)].toSet()
    }

    /** A seeded source under the repository root, so the rule can report it by a relative path. */
    private File source(String line, String name = 'Seeded.groovy') {
        def file = dir.resolve(name).toFile()
        file.text = "class ${name - '.groovy'} {\n    $line\n}\n"
        file
    }

    private static String relative(File file) {
        RepoSourceTree.relative(file)
    }
}
