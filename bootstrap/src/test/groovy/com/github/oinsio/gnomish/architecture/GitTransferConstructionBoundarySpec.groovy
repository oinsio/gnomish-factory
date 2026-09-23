package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR8, FR9, design D5 of own-git-transfer-argv: the owner's value is built by the owner's
 * factories, {@code GitTransfer.fetch} and {@code GitTransfer.clone}, and by nothing else in
 * production. {@code GitTransfer} is a public record, so its canonical constructor is public by
 * the language rule; a value built through it from data — an argv read from configuration, split
 * from a string — spells no transfer literal for {@link GitTransferBoundarySpec} to see and
 * reaches the runner through its typed entry, which the untyped entry's refusal never inspects.
 * This scan closes that path for production; the constructor stays public so specs can hand the
 * renderer and the runner a hostile value, which is what their refusals exist to reject.
 *
 * <p>Scans the same comment-stripped production view as {@link GitTransferBoundarySpec}, whose
 * reach feature already pins that view to the build's module set.
 */
class GitTransferConstructionBoundarySpec extends Specification {

    /** The two spellings of a construction that bypasses the factories. */
    private static final List<String> CONSTRUCTION = [
        'new GitTransfer(',
        'GitTransfer::new'
    ]

    /** The owner: its factories construct the value. */
    private static final String OWNER_ROOT = 'gittransfer/src/main/'

    /**
     * The seed helper's branch substitution, done factory-side so the identity spec runs the
     * owner's exact clone through the runner: it replaces {@code GitTransfer.BRANCH_PARAMETER}
     * and nothing else, as the rendered script's {@code "$1"} does. A test fixture that the
     * production walk reaches because {@code :test-fixtures} ships its sources as {@code src/main}.
     */
    private static final List<String> FIXTURES = [
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/TransferAdversaryFixture.groovy',
    ]

    // FR8, FR9: a production construction outside the owner is a second argv builder that no
    //     literal scan can see — the argv arrives as data.
    def "FR8, FR9: no production source outside the owner constructs a GitTransfer"() {
        given: 'every production source of the build, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'every file that constructs the value is the owner or a listed fixture'
        def constructing = sources.findAll {
            constructs(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        constructing.findAll { !allowlisted(it) } == []

        and: 'the allowlist is not stale: the owner and each fixture really construct one'
        constructing.any { it.startsWith(OWNER_ROOT) }
        constructing.containsAll(FIXTURES)
    }

    // Over a clean tree the scan finds nothing, so only seeded sources tell a working detector
    //     from a broken one; a comment that names the constructor must not count.
    def "the detector finds a seeded construction and leaves a commented one alone: #shape"() {
        expect:
        constructs(source) == detected

        where:
        shape | source || detected
        'a value built from data' | 'runner.run(dir, new GitTransfer(configured, environment));' || true
        'a constructor reference' | 'return pairs.map(GitTransfer::new);' || true
        'the owner factory' | 'runner.run(dir, GitTransfer.fetch(new Origin(), refspec));' || false
        'a javadoc mention' | ' * Never {@code new GitTransfer(argv, env)} outside the owner.' || false
        'a trailing comment' | 'var value = transfer; // not new GitTransfer(...)' || false
    }

    private static boolean allowlisted(String relative) {
        relative.startsWith(OWNER_ROOT) || FIXTURES.contains(relative)
    }

    private static boolean constructs(String source) {
        source.readLines().any { line ->
            def code = RepoSourceTree.codeOnly(line)
            CONSTRUCTION.any { code.contains(it) }
        }
    }
}
