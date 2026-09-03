package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.BestEffortPush
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence
import com.github.oinsio.gnomish.app.take.RevocationDetectedException
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.logtext.OperatorEvent
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The M3 zombie-fence proof over a REAL local bare git repo (task 6.7 of add-claim-heartbeat, FR7,
 * D6, U3): two holders of the SAME task branch push to one {@code origin}; because the task branch
 * is NEVER force-pushed, git's non-fast-forward refusal is the hard fence — exactly ONE push lands
 * and the late (zombie) pusher's round is rejected and follows the normal {@code Aborted} path,
 * leaving the new holder's branch untouched.
 *
 * <p>Driven at the git layer rather than through two full {@link TakeCommand} races (which would need
 * the heartbeat/reaper timing machinery, trading M3's determinism for flakiness): the production
 * round-boundary path is exercised directly and end-to-end — the real {@link GitAttemptPersistence}
 * (local round commit + {@link BestEffortPush} of {@code origin gnomish/<task>:gnomish/<task>},
 * never {@code --force}) wrapped in the real {@link RevocationCheckingAttemptPersistence}. The
 * zombie's push getting rejected while origin still advances proves at RUNTIME that no force was
 * used (a force would have overwritten origin); {@code NoForcePushGuardSpec} pins the same at the
 * source. The revocation throw is exactly the signal the engine turns into a {@code TaskOutcome
 * .Aborted} (see {@link com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence}'s contract).
 *
 * <p>Implements FR7, D6, M3 of add-claim-heartbeat.
 */
class ZombieFenceSpec extends Specification implements BareGitRepoFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String BRANCH = 'gnomish/PROJ-1'
    private static final InstanceId HOLDER = new InstanceId('gnomish', 'holder')
    private static final InstanceId ZOMBIE = new InstanceId('gnomish', 'zombie')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path origin

    def setup() {
        origin = initBareRepo(tempDir, 'origin.git')
        // Seed the task branch on origin at a shared base commit both holders diverge from.
        def seed = cloneOrigin('seed')
        new File(seed.toFile(), 'f.txt').text = 'base'
        commitAll(seed, 'base')
        assert runner.run(seed, 'branch', BRANCH).exitCode() == 0
        assert runner.run(seed, 'push', '-q', 'origin', "${BRANCH}:${BRANCH}").exitCode() == 0
    }

    /** Clones {@code origin} into a fresh worktree checked out on the task branch, with a fixed identity. */
    private Path cloneOrigin(String name, boolean onBranch = false) {
        Path dir = tempDir.resolve(name)
        assert runner.run(tempDir, 'clone', '-q', origin.toString(), dir.toString()).exitCode() == 0
        runner.run(dir, 'config', 'user.email', "${name}@b.c")
        runner.run(dir, 'config', 'user.name', name)
        if (onBranch) {
            assert runner.run(dir, 'checkout', '-q', BRANCH).exitCode() == 0
        }
        dir
    }

    private String originTip() {
        runner.run(origin, 'rev-parse', BRANCH).stdout().trim()
    }

    private String head(Path repo) {
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    /** A round-boundary persist for {@code holder} over {@code worktree}, wrapping the real git persistence. */
    private RevocationCheckingAttemptPersistence persistenceFor(Path worktree, InstanceId holder, Tracker tracker) {
        def git = new GitAttemptPersistence(runner, worktree, 'PROJ-1', ClaimEpochSource.NONE)
        new RevocationCheckingAttemptPersistence(git, tracker, REF, holder)
    }

    private static ToolTrace trace(int round) {
        new ToolTrace(new AttemptKey('PROJ-1', 'implement', round), [])
    }

    private static List<ILoggingEvent> capturePushWarns(Closure<?> emit) {
        Logger logger = (Logger) LoggerFactory.getLogger('com.github.oinsio.gnomish.adapter.git.BestEffortPush')
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logger.addAppender(appender)
        try {
            emit()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        appender.list
    }

    // FR7, D6, M3: of two holders writing the same task branch, exactly one push lands; the loser
    // gets a non-fast-forward rejection and its round throws the revocation the engine turns into
    // Aborted — no force push anywhere, the new holder's branch untouched.
    def "M3: the zombie's late push is fenced non-fast-forward and its round aborts, exactly one push landing"() {
        given: 'both holders clone the same base of the task branch; the tracker reports the NEW holder owns the claim'
        def holderWork = cloneOrigin('holder', true)
        def zombieWork = cloneOrigin('zombie', true)
        def base = originTip()
        Tracker tracker = Stub {
            fetchTask(REF) >> new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Working(HOLDER.value()), AbortFacts.none(), false)
        }

        when: 'the new holder persists a round: local commit plus a fast-forward push that lands on origin'
        new File(holderWork.toFile(), 'holder.txt').text = 'holder round'
        persistenceFor(holderWork, HOLDER, tracker).persist('PROJ-1', TaskState.atStageStart('implement'), trace(0))

        then: 'exactly the holder\'s commit is now the origin task-branch tip — the first push landed by fast-forward'
        originTip() != base
        originTip() == head(holderWork)

        when: 'the zombie thaws and persists its OWN divergent round against the same branch'
        new File(zombieWork.toFile(), 'zombie.txt').text = 'zombie round'
        RevocationDetectedException revocation = null
        def warns = capturePushWarns {
            try {
                persistenceFor(zombieWork, ZOMBIE, tracker).persist('PROJ-1', TaskState.atStageStart('implement'), trace(0))
            } catch (RevocationDetectedException e) {
                revocation = e
            }
            return
        }

        then: 'the round threw the revocation the engine turns into TaskOutcome.Aborted — the normal abort path, no park/finish'
        revocation != null
        revocation.message.contains('PROJ-1')
        revocation.message.contains("claim held by another instance (${HOLDER.value()})")

        and: 'the zombie push was actually attempted and rejected as non-fast-forward — one WARN, no force retry'
        warns.size() == 1
        warns[0].level == Level.WARN
        warns[0].formattedMessage.startsWith(OperatorEvent.PUSH_FAILED.head() + 'push failed:')

        and: 'the fence held at runtime: origin still points at the holder\'s commit, NOT the zombie\'s — no force overwrote it'
        originTip() == head(holderWork)
        originTip() != head(zombieWork)

        and: 'the zombie\'s divergent commit never reached origin — the new holder\'s branch is untouched, no data corruption'
        runner.run(holderWork, 'fetch', '-q', 'origin').exitCode() == 0
        runner.run(holderWork, 'merge-base', '--is-ancestor', head(zombieWork), "origin/${BRANCH}").exitCode() != 0
    }
}
