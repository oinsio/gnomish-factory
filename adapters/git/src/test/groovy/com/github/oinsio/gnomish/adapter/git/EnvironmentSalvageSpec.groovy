package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-sandbox-core, "Salvage of interrupted rounds" of
 * git-task-persistence: {@link EnvironmentSalvage}'s leftover probe answers
 * from the in-box working copy — dirty means salvageable, clean means nothing
 * to do — and a lost environment degrades to "nothing reachable to salvage"
 * instead of throwing or pretending there is work to commit.
 */
class EnvironmentSalvageSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = TaskIdSanitizer.branchName('SALV-1')

    @TempDir
    Path tempDir

    Path cloneDir

    private LocalBoxEnvironment materializedBox() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        box
    }

    def "FR6: an uncommitted in-box change is a leftover to salvage"() {
        given:
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        expect:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).hasLeftovers()
    }

    def "FR6: a clean in-box working copy has no leftovers"() {
        expect:
        !new EnvironmentSalvage(materializedBox(), ClaimEpochSource.NONE).hasLeftovers()
    }

    def "FR6: a dead environment probes as having nothing reachable to salvage"() {
        given: 'an environment whose exec cannot even start'
        def dead = [exec: { ExecCommand command ->
                throw new ProcessStartException('container gone', new IOException('no runtime'))
            }] as TaskExecutionEnvironment

        expect: 'the probe degrades to false — resume continues from the last harvested state'
        !new EnvironmentSalvage(dead, ClaimEpochSource.NONE).hasLeftovers()
    }

    def "FR6: salvage() commits and harvests a leftover into the factory clone"() {
        given:
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-1')

        then: 'the leftover is committed in-box and harvested to the factory clone'
        def tipAfter = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        tipAfter != tipBefore
        gitOutput(cloneDir, 'log', '-1', '--format=%s', tipAfter) == 'gnomish: salvage'
        gitOutput(cloneDir, 'show', tipAfter + ':work.txt') == 'interrupted round'

        and: 'the box is clean afterwards'
        !new EnvironmentSalvage(box, ClaimEpochSource.NONE).hasLeftovers()
    }

    // FR5, design D11 of harden-task-branch-contract: the in-box salvage applies the SAME
    // ownership policy as the host worktree salvage — factory-owned .gnomish-task/ paths come
    // from the in-box HEAD, only gnome-owned work files ride the salvage commit.
    def "FR5: salvage() restores factory-owned files from the in-box tip instead of committing them"() {
        given: 'a box whose HEAD carries a recorded state.json'
        def box = materializedBox()
        def work = box.workingCopy
        Files.createDirectories(work.resolve('.gnomish-task/decisions'))
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        gitOutput(work, 'add', '-A')
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-STATE')

        and: 'a dying round left a truncated state.json, gnome work and a decision file behind'
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{ truncated')
        Files.writeString(work.resolve('.gnomish-task/decisions/build-a0.json'), '{"asked":true}')
        new File(work.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-4')

        then: 'the harvested tip carries the gnome work and the tip\'s state.json, not the dirty one'
        def tip = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        gitOutput(cloneDir, 'show', tip + ':work.txt') == 'interrupted round'
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/state.json') == '{"recorded":true}'

        and: 'the gnome-writable decisions path is salvaged like any work file'
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/decisions/build-a0.json') == '{"asked":true}'
    }

    def "FR6: salvage() is a no-op on a clean box — no commit, no harvest"() {
        given:
        def box = materializedBox()
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-2')

        then:
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == tipBefore
    }

    def "FR6: salvage() throws GitSalvageFailedException when the in-box commit fails, and never harvests"() {
        given: 'a leftover, plus the git index lock already held by another process in-box'
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        new File(box.workingCopy.toFile(), '.git/index.lock').text = 'held by another process'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-3')

        then:
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('SALV-3')

        and: 'the factory clone tip is unchanged — the failed commit was never harvested'
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == tipBefore
    }

    def "FR6: a harvest that fails tolerantly after a successful salvage commit does not propagate"() {
        given: 'a box whose harvest always fails with a plain (non-refusal) transport error'
        def cloneDir = initWorkingRepo(tempDir, 'factory-clone-flaky')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        def flakyBox = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('flaky-box'))) {
                    @Override
                    void harvest() {
                        throw new HarvestFailedException(BRANCH, 'simulated transport failure')
                    }
                }
        flakyBox.materialize(BRANCH, null)
        new File(flakyBox.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(flakyBox, ClaimEpochSource.NONE).salvage('SALV-4')

        then: 'no exception propagates — the WARN path is taken and resume continues from the last harvested state'
        noExceptionThrown()

        and: 'the in-box commit did land even though the harvest afterward failed'
        !new EnvironmentSalvage(flakyBox, ClaimEpochSource.NONE).hasLeftovers()

        and: 'but the factory clone never saw it, since the harvest failed'
        gitOutput(cloneDir, 'log', '--format=%s', 'refs/heads/' + BRANCH) == 'init'
    }
}
