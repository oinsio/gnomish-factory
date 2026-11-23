package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileRead
import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR23 of add-sandbox-core (design D17); FR9 of extract-agent-round-runner (design D3): the
 * in-branch answer file — the {@code $GNOMISH_ANSWER_FILE} fragment names the working-copy-relative
 * {@code .gnomish-task/decisions/<stage>-a<attempt>-<token>.json} path (identical to
 * the boundary check's carve-out by construction), the boundary-time read the live round makes —
 * the one answer read over the environment channel at the handle's path (FR21, design D15) —
 * sees only that name, and the file is never eagerly removed — it rides the snapshot commit. FR13 of
 * make-checkpoint-gate-durable (design D10): the round token is part of the name,
 * so a file at the same stage and attempt under another token is never read.
 */
class BranchDecisionFileSpec extends Specification implements BareGitRepoFixture {

    static final String DECISION = '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"]}'

    static final AttemptKey KEY = new AttemptKey('PROT-1', 'implement', 1)
    static final RoundToken TOKEN = RoundToken.of('0a1b2c3d4e5f60718293a4b5c6d7e8f901234567')
    static final String TOKEN_PATH = '.gnomish-task/decisions/implement-a1-0a1b2c3d4e5f60718293a4b5c6d7e8f901234567.json'

    @TempDir
    Path tempDir

    Path cloneDir
    LocalBoxEnvironment box

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', 'gnomish/PROT-1')
        box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize('gnomish/PROT-1', null)
    }

    // Scenario: cli/manual-run / Decision requests travel by the mode's own channel
    // FR9 of extract-agent-round-runner (D3, D8): the one path is the owner's, under the contract's
    //     answer-file variable
    def "FR23, FR9: the env fragment names exactly the owner's answer file, working-copy-relative"() {
        given:
        def handle = BranchDecisionFile.open(KEY, TOKEN)

        expect:
        handle.relativePath() == TOKEN_PATH
        handle.relativePath() == EnvelopePaths.answerFile(KEY, TOKEN).relative()
        handle.envFragment() == ['GNOMISH_ANSWER_FILE': TOKEN_PATH]
    }

    def "FR23, FR21: a decision the agent wrote at the announced path is read back through the channel"() {
        given: 'the agent wrote the decision at $GNOMISH_ANSWER_FILE relative to its cwd'
        def handle = BranchDecisionFile.open(KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = DECISION

        expect: 'the boundary-time read returns the request and leaves the file in place for the snapshot'
        questionAt(handle) == 'which db?'
        Files.isRegularFile(target)
    }

    def "FR21: text at the round's path that is not an answer is refused through the box channel"() {
        given:
        def handle = BranchDecisionFile.open(KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = 'not json, just words'

        expect:
        read(handle) instanceof AnswerFileReading.Refused
    }

    // FR9, FR21 of extract-agent-round-runner: the branch medium's answer file takes the one
    //     answer read's cap, and an over-cap file is refused, never read as a request
    // Scenario: executor/agent-executor / Answer file read is capped
    def "FR21: an answer file over the cap is refused through the box channel, never read as a request"() {
        given:
        def cap = ResultFileReader.ANSWER_FILE_CAP_BYTES
        def handle = BranchDecisionFile.open(KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = '{"kind":"decision-request","question":"' + ('q' * cap) + '","options":["a","b"]}'

        when:
        def reading = read(handle)

        then:
        reading instanceof AnswerFileReading.Refused
        (reading as AnswerFileReading.Refused).cause().contains("over the cap of ${cap} bytes")
    }

    def "FR23: no decision file means an empty read, never a fabricated value"() {
        expect:
        read(BranchDecisionFile.open(KEY, TOKEN)) == new AnswerFileReading.Absent()
    }

    // Scenario: executor/agent-executor / Stale request from another round is ignored
    def "FR23: a stale decision file under another stage or attempt name is never read"() {
        given: 'leftovers from other rounds sit in the decisions directory'
        def decisions = box.workingCopy.resolve('.gnomish-task/decisions')
        Files.createDirectories(decisions)
        decisions.resolve('implement-a9.json').toFile().text = DECISION
        decisions.resolve('review-a1.json').toFile().text = DECISION

        expect: 'the current round sees only its own name'
        read(BranchDecisionFile.open(KEY, TOKEN)) == new AnswerFileReading.Absent()
    }

    // Scenario: executor/agent-executor / An answered request is not asked again
    // Scenario: executor/agent-executor / Stale request from another round is ignored
    // Scenario: executor/agent-executor / A decision file beside the real one changes nothing
    def "FR13: the read takes exactly the token path, never a same-key file under another token"() {
        given: 'this stage and attempt asked before, in a round that opened on another tip'
        def decisions = box.workingCopy.resolve('.gnomish-task/decisions')
        Files.createDirectories(decisions)
        decisions.resolve('implement-a1-ffffffffffffffffffffffffffffffffffffffff.json').toFile().text = DECISION

        and: 'the token-less name of the same key, as an older factory spelled it'
        decisions.resolve('implement-a1.json').toFile().text = DECISION

        expect: 'neither is this round\'s question'
        read(BranchDecisionFile.open(KEY, TOKEN)) == new AnswerFileReading.Absent()

        when: 'the gnome writes at this round\'s token path'
        decisions.resolve('implement-a1-' + TOKEN.commit() + '.json').toFile().text =
                '{"kind":"decision-request","question":"live","options":["a","b"]}'

        then: 'only that file is read'
        questionAt(BranchDecisionFile.open(KEY, TOKEN)) == 'live'
    }

    /** The live round's read of the handle's path: the one answer read over the box channel. */
    private AnswerFileReading read(BranchDecisionFile.Handle handle) {
        AnswerFileRead.in(box, handle.relativePath(), UntrustedText.&agent)
    }

    private String questionAt(BranchDecisionFile.Handle handle) {
        ((read(handle) as AnswerFileReading.Answered).answer() as Answer.DecisionRequest).question().forLog()
    }
}
