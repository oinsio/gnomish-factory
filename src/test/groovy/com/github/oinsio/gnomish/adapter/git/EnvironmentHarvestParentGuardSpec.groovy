package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR22 of add-sandbox-core (design D16), the parent-check of the harvested
 * state commit: not only must the parent BE the snapshot commit
 * (EnvironmentRoundProtocolSpec covers the inserted-commit case) — a harvested
 * tip whose parent cannot be read at all (here: the branch ends up at a
 * parentless commit) aborts as the same {@link RoundBoundaryViolationException},
 * never as an accidental NullPointerException, so the engine still classifies
 * it as Aborted with the evidence on the branch.
 */
class EnvironmentHarvestParentGuardSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'GUARD-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
    }

    /** Points the factory branch at a fresh parentless commit, as a corrupted harvest would. */
    private void setBranchToOrphan() {
        def tree = gitOutput(cloneDir, 'rev-parse', 'HEAD^{tree}')
        def orphan = gitOutput(cloneDir, '-c', 'user.email=d@e.f', '-c', 'user.name=d',
                'commit-tree', tree, '-m', 'orphan')
        gitOutput(cloneDir, 'update-ref', 'refs/heads/' + BRANCH, orphan)
    }

    def "FR22: a harvested state commit with no readable parent aborts as a boundary violation"() {
        given: 'a box whose state-commit harvest leaves the branch at a parentless commit'
        def box = new HijackedHarvestBox(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        def attemptRef = new AttemptCommitRef()
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        def snapshotStep = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, attemptRef)
        def persistence = new EnvironmentAttemptPersistence(box, runner, cloneDir, gitObjects, TASK, attemptRef)
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'
        snapshotStep.snapshot(TASK, 'implement', 1)
        box.hijack = { setBranchToOrphan() }

        when:
        persistence.persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(
                new AttemptKey(TASK, 'implement', 1),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-08-08T09:00:00Z'), Duration.ofMillis(100))
                ]))

        then: 'the violation is explicit, never a NullPointerException from the missing parent'
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('no readable parent')
    }
}

/** A {@link LocalBoxEnvironment} whose next harvest can be hijacked by the spec. */
class HijackedHarvestBox extends LocalBoxEnvironment {

    Closure hijack

    HijackedHarvestBox(Path cloneDir, Path boxRoot) {
        super(cloneDir, boxRoot)
    }

    @Override
    void harvest() {
        if (hijack != null) {
            def h = hijack
            hijack = null
            h.call()
            return
        }
        super.harvest()
    }
}
