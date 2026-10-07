package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of add-sandbox-core (design D15), the parsing edge of the snapshot
 * message contract: only a well-formed {@code gnomish: snapshot <stage>#<round> <token>}
 * subject at the branch tip classifies as an interrupted verification — a
 * subject with an empty stage, a missing round marker, a non-numeric round or
 * no readable round token never does, so resume falls back to the ordinary
 * salvage path instead of re-running verification against a commit that is not
 * a factory snapshot.
 *
 * <p>FR15, NFR-R4 of make-checkpoint-gate-durable (design D10): the token names the round, and the
 * request the round asked — if any — is read from the snapshot's tree at exactly that round's path.
 */
class SnapshotTipCheckSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'TIP-1'
    static final String BRANCH = 'gnomish/TIP-1'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path clone

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        commitAll(clone)
        gitOutput(clone, 'checkout', '-b', BRANCH)
    }

    private void tipWithSubject(String subject) {
        new File(clone.toFile(), 'a.txt').text = subject
        commitAll(clone, subject)
    }

    private String tip() {
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    /** Lands a file at {@code path} in a commit with {@code subject}, as the in-box snapshot does. */
    private void commitFile(String path, String content, String subject) {
        Path file = clone.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        commitAll(clone, subject)
    }

    def "FR15, FR13: a well-formed subject yields the attempt commit, stage, round, the typed token and no request"() {
        given: 'a round opened on the current tip and closed without asking'
        def token = RoundToken.of(tip())
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 3, token))

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)

        then:
        with(pending.get()) {
            attemptCommit() == tip()
            stage() == 'implement'
            round() == 3
            request().isEmpty()
        }

        and: 'FR13 (design D10 as amended): the recorded token travels on, typed — the round it names, not the snapshot'
        pending.get().token() == token
        pending.get().token() != RoundToken.of(pending.get().attemptCommit())
    }

    def "FR15: the request the round asked is read from the snapshot's tree at its token path"() {
        given:
        def token = RoundToken.of(tip())
        def path = HarvestedBoundaryCheck.decisionPath(new AttemptKey(TASK, 'implement', 0), token)
        commitFile(path, '{"question":"which db?"}', ServiceCommitMessages.snapshot('implement', 0, token))

        and: 'the working copy no longer holds it — the read is of the snapshot, not of a working copy'
        Files.delete(clone.resolve(path))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().request() == Optional.of('{"question":"which db?"}')
    }

    def "NFR-R4: a request at the same key under another round's token is not read"() {
        given: 'an earlier round left its request on the branch'
        def earlier = RoundToken.of(tip())
        commitFile(HarvestedBoundaryCheck.decisionPath(new AttemptKey(TASK, 'implement', 0), earlier),
                '{"question":"stale?"}', 'gnomish: task approved')

        and: 'the current round, opened on the next tip, asked nothing'
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 0, RoundToken.of(tip())))

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().request().isEmpty()
    }

    def "FR21, FR15: a malformed snapshot subject never classifies as an interrupted verification"() {
        given: 'a tip whose subject only imitates the snapshot message shape'
        tipWithSubject(subject)
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'no pending verification — the resume goes through salvage'
        pending.isEmpty()

        and: 'FR5 of harden-logging-observability: the factory wrote this tip, so the anomaly is traced'
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('unreadable stage#round token')

        where:
        subject << [
            'gnomish: snapshot #3 0a1b',
            'gnomish: snapshot implement 0a1b',
            'gnomish: snapshot implement#latest 0a1b',
            // The token-less subject of the earlier contract: no round to read the request of.
            'gnomish: snapshot implement#1',
            'gnomish: snapshot implement#1 ',
            'gnomish: snapshot implement#1 NOT-HEX',
            // The shape matches, the values do not: a blank stage, a round past int range.
            'gnomish: snapshot  #1 0a1b',
            'gnomish: snapshot implement#99999999999 0a1b',
        ]
    }

    def "an ordinary tip is not a snapshot and is not traced as an anomaly"() {
        given:
        tipWithSubject('gnomish: round implement#1')
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        pending.isEmpty()
        events.isEmpty()
    }

    // FR5: a tip read git refused routes the resume through salvage exactly as an ordinary tip
    // does — the DEBUG line is the only thing that distinguishes the two afterwards.
    def "FR5: a tip read git refuses is traced before it reads as 'not a snapshot'"() {
        given:
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect('NO-SUCH-BRANCH')
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        pending.isEmpty()

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('could not read gnomish/NO-SUCH-BRANCH')
    }

    // FR6: git's stderr is text from outside this process; one refused read stays one line.
    def "FR6: a malformed subject cannot forge a second log line"() {
        given:
        tipWithSubject('gnomish: snapshot impl\u001b[31m#not-a-number')
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        new SnapshotTipCheck(runner, clone).inspect(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        !events[0].formattedMessage.contains('\u001b')
    }
}
