package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1 of own-git-invocation-policy (design D8; lock-scope rule, "Specs") — eight fresh claims
 * released together by one latch, on real virtual threads, each run the fresh-claim git sequence
 * through the production adapters the fresh claim uses: {@link GitTaskRepository#createTask} (branch
 * creation, {@code worktree add}, the STARTED commit and its register transaction), then the first
 * round commit through {@link GitAttemptPersistence}. The clone carries the shared fixture's
 * adversarial local configuration, including {@code branch.autoSetupMerge=always}, the key whose
 * write raced on {@code .git/config} before the invocation policy existed.
 *
 * <p>No order between the threads is assumed: the spec waits for all of them under a generous
 * bound and then judges every outcome, whichever thread ran first.
 */
class ConcurrentCloneConfigSpec extends Specification implements BareGitRepoFixture {

    static final int CLAIMS = 8
    static final String CONFIG_LOCK = 'could not lock config file'

    @TempDir
    Path tempDir

    Path clone
    RegisteredClone registeredClone

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        gitOutput(clone, '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false', 'add', 'a.txt')
        gitOutput(clone, '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgSign=false', '-c', 'core.fsmonitor=false',
                '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-q', '-m', 'seed')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), clone)
    }

    // Scenario: git/git-invocation-policy / Eight concurrent claims on one clone
    def "NFR-R1: eight concurrent fresh claims on one clone all succeed, none on a config lock, and .git/config is byte-identical"() {
        given: 'the clone configuration before any claim, and eight claims held at one latch'
        byte[] configBefore = Files.readAllBytes(clone.resolve('.git/config'))
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(CLAIMS)
        def failures = new ConcurrentHashMap<String, Throwable>()
        def tips = new ConcurrentHashMap<String, String>()
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when: 'the latch releases every claim at once'
        (0..<CLAIMS).each { i ->
            String taskId = "CONC-${i}"
            executor.submit {
                try {
                    start.await()
                    tips[taskId] = freshClaim(taskId)
                } catch (Throwable t) {
                    failures[taskId] = t
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        boolean finished = done.await(120, TimeUnit.SECONDS)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        then: 'every claim finished within the bound'
        finished

        and: 'no claim failed on the configuration lock, nor on anything else'
        failures.values().every { !messages(it).contains(CONFIG_LOCK) }
        failures.isEmpty()

        and: "every claim's branch carries its first round commit, registered"
        tips.size() == CLAIMS
        tips.every { taskId, tip ->
            gitOutput(clone, 'rev-parse', FactoryRegisterRefs.of(taskId).name()) == tip &&
            gitOutput(clone, 'log', '-1', '--format=%s', tip) == ServiceCommitMessages.round('implement', 1)
        }

        and: 'nothing planted ran, and the clone configuration is byte-identical'
        assertNothingPlantedRan(clone)
        Files.readAllBytes(clone.resolve('.git/config')) == configBefore
    }

    /** One fresh claim's git sequence, through its own runner and repository; answers the registered tip. */
    private String freshClaim(String taskId) {
        def runner = new GitProcessRunner()
        new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock()).createTask(
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                TaskStart.commit(clone, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        Path worktree = registeredClone.worktrees().resolve(taskId)
        new File(worktree.toFile(), 'gnome.txt').text = "work by ${taskId}"
        new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE).persist(taskId,
                TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(taskId, 'implement', 1), []))
        runner.run(clone, 'rev-parse', FactoryRegisterRefs.of(taskId).name()).stdout().forParsing().trim()
    }

    /** Every message on the cause chain, so a lock error wrapped by an adapter exception is still seen. */
    private static String messages(Throwable t) {
        List<String> all = []
        for (Throwable c = t; c != null; c = c.cause == c ? null : c.cause) {
            all << String.valueOf(c.message)
        }
        all.join('\n')
    }
}
