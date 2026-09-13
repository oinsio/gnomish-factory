package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.OriginContact
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR8, D7 of add-base-ref-resolution (ADR 0006): a base name that merely LOOKS like an
 * abbreviated commit must never be answered out of the clone's own ref namespaces.
 *
 * <p>The trap is git's: {@code rev-parse <name>^{commit}} applies the full gitrevisions lookup
 * order, so a local branch or tag whose name happens to be hex wins over the object of the same
 * abbreviation — and the refresh would then report the operator's stale local ref as this claim's
 * base commit, with no fetch and no refusal. A full 40/64-character name is immune (git ignores
 * refs that are whole object names), which is why only the abbreviation arm needs the guard.
 */
class HexNamedRefBaseRefreshSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    private Path work
    private Path origin
    private Path clone

    /** The full commit of origin's {@code develop}, and the abbreviation a local ref will shadow. */
    private String held
    private String abbreviation
    private String elsewhere

    def setup() {
        (work, origin, clone) = initBaseRefTopology(tempDir)

        held = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop')
        abbreviation = held.substring(0, 10)
        elsewhere = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/main')
        assert elsewhere != held
    }

    private BaseRefresh refresh() {
        new BaseRefresh(runner, VirtualTimeGitRetries.gitInfrastructure())
    }

    def "FR6: a local #namespace named like an abbreviated commit never becomes the base"() {
        given: "the clone holds the object, and a local ${namespace} of that hex name points elsewhere"
        assert gitExitCode(clone, ref, abbreviation, elsewhere) == 0

        when:
        def outcome = refresh().refresh(clone, abbreviation)

        then: "the operator's local ref is never the answer"
        !(outcome instanceof BaseRefreshOutcome.Refreshed
                && (outcome as BaseRefreshOutcome.Refreshed).commit() == elsewhere)

        and: 'origin holds no such name either, so the task parks with a report'
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains(abbreviation)

        where:
        namespace | ref
        'branch' | 'branch'
        'tag' | 'tag'
    }

    def "FR7: a pinned COMMIT is resolved as an object, never out of a hex-named local branch"() {
        given:
        assert gitExitCode(clone, 'branch', abbreviation, elsewhere) == 0

        when:
        def outcome = refresh().refresh(clone, abbreviation, BaseRefKind.COMMIT)

        then: 'the pin names an object this clone cannot name unambiguously — a refusal, not the branch tip'
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains(abbreviation)
    }

    def "FR8: the abbreviation still resolves offline when no ref shadows it"() {
        when:
        def outcome = refresh().refresh(clone, abbreviation)

        then:
        outcome == new BaseRefreshOutcome.Refreshed(abbreviation, held, BaseRefKind.COMMIT, OriginContact.CLONE_ONLY)
    }

    def "FR6: a full object name resolves to the object even when a ref of that name exists"() {
        given: 'a ref git itself ignores: one whose whole name is an object name'
        assert gitExitCode(clone, 'update-ref', 'refs/heads/' + held, elsewhere) == 0

        when:
        def outcome = refresh().refresh(clone, held)

        then:
        outcome == new BaseRefreshOutcome.Refreshed(held, held, BaseRefKind.COMMIT, OriginContact.CLONE_ONLY)
    }
}
