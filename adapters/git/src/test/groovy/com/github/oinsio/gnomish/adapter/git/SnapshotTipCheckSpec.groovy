package com.github.oinsio.gnomish.adapter.git

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

        expect: 'no stage before the round marker (or no parsable round) means no pending verification'
        new SnapshotTipCheck(runner, clone).inspect(BRANCH).isEmpty()

        where:
        subject << [
            'gnomish: snapshot #3',
            'gnomish: snapshot implement',
            'gnomish: snapshot implement#latest',
        ]
    }
}
