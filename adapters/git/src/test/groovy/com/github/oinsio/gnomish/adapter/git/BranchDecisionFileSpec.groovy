package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR23 of add-sandbox-core (design D17): the in-branch decision protocol —
 * the {@code $GNOMISH_DECISION_FILE} fragment names the working-copy-relative
 * {@code .gnomish-task/decisions/<stage>-a<attempt>-<token>.json} path (identical to
 * the boundary check's carve-out by construction), the boundary-time read goes
 * through the environment channel, stale names are excluded, and the file is
 * never eagerly removed — it rides the snapshot commit. FR13 of
 * make-checkpoint-gate-durable (design D10): the round token is part of the name,
 * so a file at the same stage and attempt under another token is never read.
 */
class BranchDecisionFileSpec extends Specification implements BareGitRepoFixture {

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
    def "FR23: the env fragment names exactly the boundary check's carved-out path, working-copy-relative"() {
        given:
        def handle = BranchDecisionFile.open(box, KEY, TOKEN)

        expect:
        handle.relativePath() == TOKEN_PATH
        handle.relativePath() == GnomeRangeInspection.decisionPath(KEY, TOKEN)
        handle.envFragment() == ['GNOMISH_DECISION_FILE': TOKEN_PATH]
    }

    def "FR23: a decision the agent wrote at the announced path is read back through the channel"() {
        given: 'the agent wrote the decision at $GNOMISH_DECISION_FILE relative to its cwd'
        def handle = BranchDecisionFile.open(box, KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = '{"question":"which db?"}'

        expect: 'the boundary-time read returns the request and leaves the file in place for the snapshot'
        handle.read().get().question().forLog() == 'which db?'
        Files.isRegularFile(target)
    }

    // FR6 of define-executor-contract: the in-branch read goes through the shared result reader,
    //     so the box medium gets the host medium's tolerant fallback and cap, not a copy of them.
    // Scenario: executor/result-channel / Garbage decision file keeps its fallback
    def "FR6: garbage at the round's path becomes the question verbatim, read through the shared reader"() {
        given:
        def handle = BranchDecisionFile.open(box, KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = 'not json, just words'

        when:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)
        def request = handle.read()
        logs.detach()

        then:
        request.get().question().forLog() == 'not json, just words'
        request.get().question().provenance() == Provenance.AGENT
        request.get().options().isEmpty()
    }

    def "FR6: a decision file over the cap is read up to the cap through the box channel, and still asks"() {
        given:
        def cap = ResultFileReader.DECISION_FILE_CAP_BYTES
        def handle = BranchDecisionFile.open(box, KEY, TOKEN)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = '{"question":"' + ('q' * cap) + '"}'

        when:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)
        def request = handle.read()
        logs.detach()

        then: 'the cut text is the question, ending with the reader\'s truncation marker'
        def marker = new ResultFileReader(cap, { String text ->
            UntrustedText.agent(text)
        }).truncationMarker()
        request.get().question().length() == cap + marker.length()
        request.get().options().isEmpty()
    }

    def "FR23: no decision file means an empty read, never a fabricated value"() {
        expect:
        BranchDecisionFile.open(box, KEY, TOKEN).read().isEmpty()
    }

    // Scenario: executor/agent-executor / Stale request from another round is ignored
    def "FR23: a stale decision file under another stage or attempt name is never read"() {
        given: 'leftovers from other rounds sit in the decisions directory'
        def decisions = box.workingCopy.resolve('.gnomish-task/decisions')
        Files.createDirectories(decisions)
        decisions.resolve('implement-a9.json').toFile().text = '{"question":"stale attempt"}'
        decisions.resolve('review-a1.json').toFile().text = '{"question":"stale stage"}'

        expect: 'the current round sees only its own name'
        BranchDecisionFile.open(box, KEY, TOKEN).read().isEmpty()
    }

    // Scenario: executor/agent-executor / An answered request is not asked again
    // Scenario: executor/agent-executor / Stale request from another round is ignored
    // Scenario: executor/agent-executor / A decision file beside the real one changes nothing
    def "FR13: the read takes exactly the token path, never a same-key file under another token"() {
        given: 'this stage and attempt asked before, in a round that opened on another tip'
        def decisions = box.workingCopy.resolve('.gnomish-task/decisions')
        Files.createDirectories(decisions)
        decisions.resolve('implement-a1-ffffffffffffffffffffffffffffffffffffffff.json').toFile().text = '{"question":"answered"}'

        and: 'the token-less name of the same key, as an older factory spelled it'
        decisions.resolve('implement-a1.json').toFile().text = '{"question":"older spelling"}'

        expect: 'neither is this round\'s question'
        BranchDecisionFile.open(box, KEY, TOKEN).read().isEmpty()

        when: 'the gnome writes at this round\'s token path'
        decisions.resolve('implement-a1-' + TOKEN.commit() + '.json').toFile().text = '{"question":"live"}'

        then: 'only that file is read'
        BranchDecisionFile.open(box, KEY, TOKEN).read().get().question().forLog() == 'live'
    }
}
