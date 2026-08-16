package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR23 of add-sandbox-core (design D17): the in-branch decision protocol —
 * the {@code $GNOMISH_DECISION_FILE} fragment names the working-copy-relative
 * {@code .gnomish-task/decisions/<stage>-a<attempt>.json} path (identical to
 * the boundary check's carve-out by construction), the boundary-time read goes
 * through the environment channel, stale names are excluded, and the file is
 * never eagerly removed — it rides the snapshot commit.
 */
class BranchDecisionFileSpec extends Specification implements BareGitRepoFixture {

    static final AttemptKey KEY = new AttemptKey('PROT-1', 'implement', 1)

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

    def "FR23: the env fragment names exactly the boundary check's carved-out path, working-copy-relative"() {
        given:
        def handle = BranchDecisionFile.open(box, KEY)

        expect:
        handle.relativePath() == '.gnomish-task/decisions/implement-a1.json'
        handle.relativePath() == HarvestedBoundaryCheck.decisionPath(KEY)
        handle.envFragment() == ['GNOMISH_DECISION_FILE': '.gnomish-task/decisions/implement-a1.json']
    }

    def "FR23: a decision the agent wrote at the announced path is read back through the channel"() {
        given: 'the agent wrote the decision at $GNOMISH_DECISION_FILE relative to its cwd'
        def handle = BranchDecisionFile.open(box, KEY)
        def target = box.workingCopy.resolve(handle.relativePath())
        Files.createDirectories(target.parent)
        target.toFile().text = '{"question":"which db?"}'

        expect: 'the boundary-time read returns the raw content and leaves the file in place for the snapshot'
        handle.read().get() == '{"question":"which db?"}'
        Files.isRegularFile(target)
    }

    def "FR23: no decision file means an empty read, never a fabricated value"() {
        expect:
        BranchDecisionFile.open(box, KEY).read().isEmpty()
    }

    def "FR23: a stale decision file under another stage or attempt name is never read"() {
        given: 'leftovers from other rounds sit in the decisions directory'
        def decisions = box.workingCopy.resolve('.gnomish-task/decisions')
        Files.createDirectories(decisions)
        decisions.resolve('implement-a9.json').toFile().text = '{"question":"stale attempt"}'
        decisions.resolve('review-a1.json').toFile().text = '{"question":"stale stage"}'

        expect: 'the current round sees only its own name'
        BranchDecisionFile.open(box, KEY).read().isEmpty()
    }
}
