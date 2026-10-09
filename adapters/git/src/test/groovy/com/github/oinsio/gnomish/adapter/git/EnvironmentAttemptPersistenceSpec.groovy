package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR15 of make-checkpoint-gate-durable (design D10, amended 2026-10-07): the sandboxed
 * persistence judges a round by the closed round its {@link CurrentRound} cell holds — the token
 * is both the carve-out's name and the diff base, on a live round and on a resumed one alike — and
 * reads no tip in place of a round the cell does not hold (alternative C rejected).
 */
class EnvironmentAttemptPersistenceSpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    static final String TASK = 'PERS-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    GitObjects gitObjects

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
    }

    private LocalBoxEnvironment materializedBox(String name = 'box') {
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve(name)))
        box.materialize(BRANCH, null)
        box
    }

    private EnvironmentAttemptPersistence persistence(LocalBoxEnvironment box, CurrentRound rounds, GitProcessRunner git = runner) {
        new EnvironmentAttemptPersistence(box, git, cloneDir, gitObjects, TASK, rounds, ClaimEpochSource.NONE)
    }

    private String tip() {
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    /** A factory lifecycle commit on the task branch touching the state directory, as appendDecision does. */
    private void lifecycleCommitOnBranch() {
        gitOutput(cloneDir, 'checkout', '-q', BRANCH)
        Files.createDirectories(cloneDir.resolve('.gnomish-task')).resolve('task.json').toFile().text = '{"outcome":null}'
        commitAll(cloneDir, 'gnomish: task resumed')
        gitOutput(cloneDir, 'checkout', '-q', '-')
    }

    private static void write(LocalBoxEnvironment box, String path, String content) {
        def file = box.workingCopy.resolve(path).toFile()
        file.parentFile.mkdirs()
        file.text = content
    }

    private static ToolTrace trace(int round) {
        new ToolTrace(new AttemptKey(TASK, 'implement', round), [])
    }

    private void persistRound(EnvironmentAttemptPersistence persistence, int round) {
        persistence.persist(TASK, TaskState.atStageStart('implement'), trace(round))
    }

    def "FR13: the diff base is the round's token, not the tip the persistence saw when it was built"() {
        given: 'the persistence is built, then a lifecycle commit moves the tip through the state directory'
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        def persistence = persistence(box, rounds)
        lifecycleCommitOnBranch()

        and: 'a round opens on the new tip and snapshots work that leaves the state directory alone'
        box.materialize(BRANCH, null)
        def openTip = tip()
        OpenedRound.reopen(rounds, cloneDir, BRANCH)
        write(box, 'work.txt', 'gnome work')
        new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, rounds).snapshot(TASK, 'implement', 0)

        when:
        persistRound(persistence, 0)

        then: 'the factory-written task.json is not blamed on the gnome: the diff starts at the token'
        rounds.closed().token().commit() == openTip
        gitOutput(cloneDir, 'log', '-1', '--format=%s', tip()) == 'gnomish: round implement#0'
    }

    def "FR16: the carve-out admits the decision file named by the round's own token"() {
        given:
        def rounds = OpenedRound.at(cloneDir, BRANCH)
        def box = materializedBox()
        def token = rounds.opened().commit()
        write(box, ".gnomish-task/decisions/implement-a1-${token}.json", '{"question":"which db?"}')
        def snapshot = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, rounds).snapshot(TASK, 'implement', 1)

        when:
        persistRound(persistence(box, rounds), 1)

        then: 'the state commit lands on the snapshot'
        gitOutput(cloneDir, 'rev-parse', tip() + '^') == snapshot
    }

    def "FR15: a resumed round is judged against its recorded token, so a forged state write in its snapshot is caught"() {
        given: 'a round that wrote under .gnomish-task/ and was killed between its snapshot and its state commit'
        def box = materializedBox()
        def live = OpenedRound.at(cloneDir, BRANCH)
        write(box, '.gnomish-task/state.json', '{"forged":true}')
        new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, live).snapshot(TASK, 'implement', 1)

        and: 'the pickup restores the round from the snapshot record into a fresh cell'
        def resumed = new CurrentRound()
        resumed.restore(new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get())

        when: 'a persistence built after the snapshot — whose tip is the snapshot itself — persists it'
        persistRound(persistence(box, resumed), 1)

        then: 'the diff ran from the recorded token, not from a re-read tip that would compare the snapshot with itself'
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('.gnomish-task/state.json')
    }

    def "FR15: a resumed round's own decision file passes the carve-out under its recorded token"() {
        given:
        def box = materializedBox()
        def live = OpenedRound.at(cloneDir, BRANCH)
        def token = live.opened().commit()
        write(box, ".gnomish-task/decisions/implement-a1-${token}.json", '{"question":"which db?"}')
        def snapshot = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, live).snapshot(TASK, 'implement', 1)
        def resumed = new CurrentRound()
        resumed.restore(new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get())

        when:
        persistRound(persistence(box, resumed), 1)

        then:
        gitOutput(cloneDir, 'rev-parse', tip() + '^') == snapshot
        gitOutput(cloneDir, 'log', '-1', '--format=%s', tip()) == 'gnomish: round implement#1'
    }

    def "FR13: a persist with no round in the cell throws and never reads the tip in its place"() {
        given: 'a git that cannot resolve any tip, so a fallback read would surface as a tip failure'
        def box = materializedBox()
        def before = tip()
        def blind = new GitProcessRunner(gitFailingOn(tempDir, 'rev-parse').toString())

        when:
        persistRound(persistence(box, new CurrentRound(), blind), 0)

        then: 'the empty cell is the failure — not a tip resolution, and nothing was written'
        def ex = thrown(IllegalStateException)
        ex.message.contains('no round was opened')
        tip() == before
    }
}
