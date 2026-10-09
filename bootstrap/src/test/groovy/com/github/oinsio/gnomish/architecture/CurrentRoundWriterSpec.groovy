package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D7/D10 of make-checkpoint-gate-durable: a round's identity has one parse and one cell.
 * {@code RoundToken.of} is called by exactly two producers — {@code SandboxRoundEnvironmentSource}
 * mints a fresh round's token over the open tip, {@code SnapshotTipCheck} reuses a resumed round's
 * token from its snapshot subject (never mints) — and the per-run {@code CurrentRound} cell has
 * exactly three writers, each in one file: {@code open} at round open, {@code snapshotted} at the
 * snapshot, {@code restore} on the resume path. The types make a consumer of a closed round unable
 * to compile against an open one; what they cannot stop is a fourth writer — a second minting of
 * the token from some other tip read, or a component recording a round it did not open — and that
 * is the two-recovery-owners shape {@code crash-consistency.md} item 3 forbids. This scan stops it.
 *
 * <p>A writer is a call of {@code open}/{@code snapshotted}/{@code restore} on a receiver that is a
 * {@code CurrentRound}: the scan collects, across the whole tree, every name declared with that
 * type (fields, parameters, record components, accessors, locals initialized with {@code new
 * CurrentRound()}), and matches the three calls on those names and on {@code new CurrentRound()}
 * itself. The collection is asserted to have found the cell's production name, so a refactoring
 * that renamed every holder cannot silently leave the writer scan matching nothing. The cell's own
 * {@code restore} runs {@code open} and {@code snapshotted} unqualified — that is the owner, not a
 * writer.
 *
 * <p><b>{@code :test-fixtures} is excluded, on purpose.</b> {@code ClosedRounds} there opens and
 * snapshots a cell at a fixed token for the check-runner specs, standing in for the two producers
 * the way every fake stands in for its adapter; it ships in no artifact and is no recovery owner
 * of anything. Allowlisting it would pin a fixture to keep calling the writers; scanning it would
 * fail on the one legitimate test stand-in. Every other production source is scanned.
 *
 * <p>FR13, FR15 of make-checkpoint-gate-durable.
 */
class CurrentRoundWriterSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    private static final String SOURCE = GIT + 'SandboxRoundEnvironmentSource.java'

    private static final String SNAPSHOT_CHECK = GIT + 'SnapshotTipCheck.java'

    private static final String SNAPSHOT = GIT + 'EnvironmentRoundSnapshot.java'

    private static final String RESUME =
    'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/ResumeVerificationStageExecutor.java'

    private static final Pattern MINT = ~/\bRoundToken\s*\.\s*of\s*\(|import\s+static\s+[\w.]*\bRoundToken\.(of|\*)\s*;/

    private static final Pattern DECLARED = ~/\bCurrentRound\s+(\w+)\b|\b(\w+)\s*=\s*new\s+CurrentRound\s*\(/

    // FR13, FR15: one parse, two producers — a fresh round mints, a resumed one reuses its record
    def "FR13, FR15: RoundToken.of is called in production only by the round source and the snapshot check"() {
        given:
        def sources = scanned()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def minters = sources.findAll { RepoSourceTree.code(it) =~ MINT }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'both producers reached, nothing else'
        minters == [SNAPSHOT_CHECK, SOURCE].sort()
    }

    // FR13, FR15: each of the cell's three writers is called from its one owner and nowhere else
    def "FR13, FR15: CurrentRound.#writer is called in production only by #owner"() {
        given:
        def sources = scanned()
        def receivers = receiverNames(sources)

        expect: 'the receiver collection found the cell\'s production holders'
        receivers.contains('rounds')

        when:
        def callers = sources.findAll {
            writes(RepoSourceTree.code(it), receivers, writer)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'the owner is reached and is the only caller'
        callers == [owner]

        where:
        writer | owner
        'open' | SOURCE
        'snapshotted' | SNAPSHOT
        'restore' | RESUME
    }

    // The detectors are the gate — seeded calls must be found, comments and other receivers must not
    def "the writer detector: #shape"() {
        expect:
        writes(RepoSourceTree.codeOnly(line), ['rounds', 'cell'] as Set, writer) == detected

        where:
        shape | writer | line || detected
        'a field writer' | 'open' | 'rounds.open(RoundToken.of(tip));' || true
        'an accessor writer' | 'snapshotted' | 'pieces.rounds().snapshotted(commit);' || true
        'a fresh cell' | 'restore' | 'new CurrentRound().restore(p);' || true
        'another holder' | 'restore' | 'cell . restore(p);' || true
        'another receiver' | 'open' | 'GitObjects.open(dir, index);' || false
        'a trailing comment' | 'open' | 'var t = rounds.opened(); // rounds.open(t)' || false
        'a reader' | 'open' | 'var t = rounds.opened();' || false
    }

    def "the mint detector: #shape"() {
        expect:
        (RepoSourceTree.codeOnly(line) =~ MINT).find() == detected

        where:
        shape | line || detected
        'a parse' | 'RoundToken token = RoundToken.of(subject.group(3));' || true
        'a static import' | 'import static com.github.oinsio.gnomish.app.port.git.RoundToken.of;' || true
        'a javadoc mention' | ' * parsed through {@link RoundToken#of}' || false
        'a reader' | 'String c = token.commit();' || false
    }

    def "the receiver collection: #shape"() {
        expect:
        receiverNamesIn(line) == names as Set

        where:
        shape | line || names
        'a field' | 'private final CurrentRound rounds;' || ['rounds']
        'a record component' | 'record P(CurrentRound cell, int n) {}' || ['cell']
        'an accessor' | 'CurrentRound rounds() {' || ['rounds']
        'a var local' | 'var fresh = new CurrentRound();' || ['fresh']
        'an unrelated type' | 'CurrentRoundView view;' || []
    }

    /** The scanned production tree: every source but the test-support fixtures (see the javadoc). */
    private static List<File> scanned() {
        RepoSourceTree.productionSources { String path ->
            !path.startsWith('test-fixtures/')
        }
    }

    private static Set<String> receiverNames(List<File> sources) {
        sources.collectMany { receiverNamesIn(RepoSourceTree.code(it)) } as Set
    }

    private static Set<String> receiverNamesIn(String code) {
        def matcher = code =~ DECLARED
        def names = [] as Set<String>
        while (matcher.find()) {
            names << (matcher.group(1) ?: matcher.group(2))
        }
        names
    }

    private static boolean writes(String code, Set<String> receivers, String writer) {
        def holders = receivers.collect { Pattern.quote(it) }.join('|')
        def receiver = "(?:\\b(?:${holders})\\b(?:\\s*\\(\\s*\\))?|new\\s+CurrentRound\\s*\\(\\s*\\))"
        (code =~ /${receiver}\s*\.\s*${writer}\s*\(/).find()
    }
}
