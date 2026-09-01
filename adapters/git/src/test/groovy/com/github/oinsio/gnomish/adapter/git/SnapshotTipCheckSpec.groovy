package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of add-sandbox-core (design D15), the parsing edge of the snapshot
 * message contract: only a well-formed {@code gnomish: snapshot <stage>#<round>}
 * subject at the branch tip classifies as an interrupted verification — a
 * subject with an empty stage, a missing round marker, or a non-numeric round
 * never does, so resume falls back to the ordinary salvage path instead of
 * re-running verification against a commit that is not a factory snapshot.
 * (The happy-path classification lives in EnvironmentRoundProtocolSpec.)
 */
class SnapshotTipCheckSpec extends Specification implements BareGitRepoFixture {

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

    def "FR21: a malformed snapshot subject never classifies as an interrupted verification"() {
        given: 'a tip whose subject only imitates the snapshot message shape'
        tipWithSubject(subject)
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect(BRANCH)
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'no stage before the round marker (or no parsable round) means no pending verification'
        pending.isEmpty()

        and: 'FR5 of harden-logging-observability: the factory wrote this tip, so the anomaly is traced'
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('unreadable stage#round')

        where:
        subject << [
            'gnomish: snapshot #3',
            'gnomish: snapshot implement',
            'gnomish: snapshot implement#latest',
        ]
    }

    // FR5: a tip read git refused routes the resume through salvage exactly as an ordinary tip
    // does — the DEBUG line is the only thing that distinguishes the two afterwards.
    def "FR5: a tip read git refuses is traced before it reads as 'not a snapshot'"() {
        given:
        def logs = LogCaptureSupport.attach(SnapshotTipCheck, Level.DEBUG)

        when:
        def pending = new SnapshotTipCheck(runner, clone).inspect('gnomish/NO-SUCH-BRANCH')
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
        new SnapshotTipCheck(runner, clone).inspect(BRANCH)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        !events[0].formattedMessage.contains('\u001b')
    }
}
