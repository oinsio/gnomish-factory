package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R2, D8 of add-factory-serve, "Concurrent slots share one clone safely": several slot
 * lifecycles (worktree add, in-worktree commit churn, fetch, push, worktree remove) run truly
 * concurrently — real virtual threads, no fixed interleaving — against ONE shared clone of a local
 * bare repo, each through its OWN {@link GitProcessRunner} instance (mirroring how every call site
 * in this codebase constructs a fresh one). Asserts the core NFR-R2 property (no git-level
 * corruption or spurious contention failure, every branch lands on the remote correctly) plus, as
 * secondary evidence, that the repo-level mutating calls this scenario drives were actually
 * serialized: a {@code git} wrapper "binary" logs a start/end timestamp around each mutating
 * subcommand (with an artificial delay to widen the race window), and the log's intervals must
 * never overlap.
 */
class CloneMutationConcurrencySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "N concurrent slot lifecycles against one clone never corrupt it, and every branch is pushed correctly"() {
        given: 'one shared clone with an origin remote, and a logging git wrapper to observe mutating calls'
        def bare = initBareRepo(tempDir, 'origin.git')
        def seedRunner = new GitProcessRunner()
        def cloneDir = initWorkingRepo(tempDir, 'shared-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        seedRunner.run(cloneDir, 'add', 'seed.txt')
        seedRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'seed')
        seedRunner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        seedRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')

        def gitWrapper = StandIn.recording(tempDir, 'trace-clone-mutations')
        def registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)

        and: 'N slots, each with its own GitProcessRunner instance, coordinated to start together'
        int slots = 4
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(slots)
        def failures = new ConcurrentLinkedQueue()
        def localTips = new ConcurrentHashMap<String, String>()
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when: 'all slots run their lifecycle concurrently'
        (0..<slots).each { i ->
            def taskId = "PROJ-${i}"
            executor.submit({
                try {
                    start.await()
                    def runner = new GitProcessRunner(gitWrapper.toString())
                    def branchCreator = new TaskBranchCreator(runner)
                    def worktreeManager = new TaskWorktreeManager(runner, registeredClone)
                    def push = new BranchPush(runner)

                    def branchName = (branchCreator.createBranch(cloneDir, taskId, TaskStart.commit(cloneDir, 'HEAD'))
                            as BranchCreationResult.Created).branchName()
                    def worktree = worktreeManager.ensureWorktree(taskId, branchName)

                    new File(worktree.toFile(), "${taskId}.txt").text = "work by ${taskId}"
                    runner.run(worktree, 'add', "${taskId}.txt")
                    runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', "round by ${taskId}")
                    localTips[taskId] = runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim()

                    // Exercises a mutating fetch issued with cwd INSIDE the worktree (like
                    // the replica-pair reconciler/TaskBranchLocator do) — the branch is not yet on the
                    // remote at this point, so this is expected to fail harmlessly; the point here
                    // is only that it participates in the same clone's mutation lock without
                    // corrupting anything.
                    def trackingRef = "refs/remotes/origin/${branchName}"
                    fetchFromOrigin(worktree, "refs/heads/${branchName}:${trackingRef}")

                    push.pushBestEffort(worktree, branchName)

                    def cleanup = new TaskWorktreeCleanup(runner)
                    def outcome = new TaskOutcome.Completed(TaskState.atStageStart('build'))
                    cleanup.cleanUp(cloneDir, worktree, outcome)
                } catch (Throwable t) {
                    failures << t
                } finally {
                    done.countDown()
                }
            })
        }
        start.countDown()
        boolean finished = done.await(30, TimeUnit.SECONDS)
        // Not close(): under a dropped-unlock mutant the losing slots are parked forever in the
        // non-interruptible lock.lock(), and close() would join them forever before the `finished`
        // assertion below could go red — bounded shutdown keeps the hang observable as a failure.
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        then: 'every slot finished without a git-level corruption or spurious failure'
        finished
        failures.isEmpty()

        and: 'every task branch landed on the remote at exactly its local tip'
        (0..<slots).every { i ->
            def taskId = "PROJ-${i}"
            def branchName = "gnomish/${taskId}"
            def remoteTip = seedRunner.run(bare, 'rev-parse', branchName).stdout().forParsing().trim()
            remoteTip == localTips[taskId]
        }

        and: 'the mutating git calls this scenario drove were serialized: no two recorded intervals overlap'
        !StandInLog.blocks(gitWrapper).isEmpty()
        !intervalsOverlap(StandInLog.blocks(gitWrapper))
    }

    /**
     * Whether two recorded mutations overlapped. The {@code trace-clone-mutations} stand-in records
     * a block when a clone-mutating command (fetch, push, worktree add|remove|prune) starts and a
     * {@code pid}/{@code exit} block when it ends, all appended to one log in the order they
     * happened — so a start recorded while another process's command is still open is an overlap.
     * No clock is read: the order of appends is the timeline.
     */
    private static boolean intervalsOverlap(List<Map<String, String>> blocks) {
        Set<String> open = []
        for (Map<String, String> block : blocks) {
            if (block.containsKey('exit')) {
                open.remove(block.pid)
            } else {
                if (!open.isEmpty()) {
                    return true
                }
                open.add(block.pid)
            }
        }
        false
    }
}
