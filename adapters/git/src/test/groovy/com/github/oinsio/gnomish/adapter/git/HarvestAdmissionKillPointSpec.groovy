package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The kill-point row of the harvest admission (design D18 of tighten-spec-contract; NFR-R1 of
 * harden-task-branch-contract): the mid-round admission moved the branch in the clone, and the
 * instance died before the push that follows it. Over a real bare origin, a real box and the real
 * admission: the frozen state is an open gnome range above an unmoved register — locally, and at
 * origin the last closing commit with its register; a pickup elsewhere reads the register origin
 * holds; the next harvest of the same range re-admits it as an ordinary open round without a move;
 * and that second recovery is a no-op.
 */
class HarvestAdmissionKillPointSpec extends Specification implements LifecyclePushFixture, RegisteredBranchFixture {

    @TempDir
    Path tempDir

    LocalBoxEnvironment box
    HarvestAdmission admission
    AdmissionRule midRound
    String closing

    def setup() {
        initLifecyclePushFixture()
        closing = commitOnTaskBranch('started')
        assert new ClosingPush(git).push(cloneDir, TASK_ID).exitCode() == 0
        box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        admission = new HarvestAdmission(git, cloneDir)
        midRound = AdmissionRule.midRound(new AttemptKey(TASK_ID, 'implement', 0), RoundToken.of(closing))
    }

    private String at(Path repo, String ref) {
        def read = git.run(repo, 'rev-parse', '--verify', '--quiet', ref)
        read.exitCode() == 0 ? read.stdout().forParsing().trim() : null
    }

    private Map<String, String> refsOf(Path repo) {
        gitOutput(repo, 'for-each-ref', '--format=%(refname) %(objectname)').readLines().collectEntries {
            def (name, id) = it.split(' ')
            [(name): id]
        }
    }

    // Scenario: git/git-task-persistence / Killed between admission and push
    def "NFR-R1: killed between the admission and its push, the range is re-admitted as an open round, twice over"() {
        given: 'the gnome commits in its box, and a mid-round harvest admits it'
        commit(box.workingCopy, 'work.txt', 'gnome work')
        box.harvest()
        def top = admission.quarantined(TASK_ID)
        assert admission.admit(TASK_ID, top, midRound) instanceof AdmittedRange.Admitted

        when: 'the instance dies here — before pushAdmitted'
        def frozen = refsOf(cloneDir)

        then: 'locally an open gnome range above an unmoved register; at origin the last closing commit and its register'
        at(cloneDir, "refs/heads/${BRANCH}") == top
        at(cloneDir, 'refs/gnomish-factory/T-1') == closing
        at(origin, "refs/heads/${BRANCH}") == closing
        at(origin, 'refs/gnomish-factory/T-1') == closing

        when: 'a pickup in another clone fetches the branch and the register from origin'
        def peer = tempDir.resolve('peer')
        seedClone(tempDir, origin.toString(), peer, '-q')
        new TouchpointFetch(git).fetch(peer, TASK_ID,
                new com.github.oinsio.gnomish.gittransfer.Refspec("+${BRANCH}:refs/heads/${BRANCH}"), FactoryRegisterRefs.fetchedInPlace(TASK_ID))

        then: 'the register names the last closing commit there'
        registeredId(peer, TASK_ID) == closing

        when: 'the next harvest of the same box runs, and then once more'
        box.harvest()
        def again = admission.admit(TASK_ID, admission.quarantined(TASK_ID), midRound)
        def afterFirst = refsOf(cloneDir)
        box.harvest()
        def twice = admission.admit(TASK_ID, admission.quarantined(TASK_ID), midRound)

        then: 'the range is admitted as an ordinary open round, nothing moves, and the second recovery is a no-op'
        again instanceof AdmittedRange.Admitted
        twice instanceof AdmittedRange.Admitted
        afterFirst == frozen
        refsOf(cloneDir) == frozen

        when: 'the push the dead instance owed runs on the next admission'
        admission.pushAdmitted(TASK_ID)

        then: 'origin carries the range, and its register still names the last closing commit'
        at(origin, "refs/heads/${BRANCH}") == top
        at(origin, 'refs/gnomish-factory/T-1') == closing
    }
}
