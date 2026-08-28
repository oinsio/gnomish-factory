package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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

        def logFile = tempDir.resolve('mutation.log')
        def gitWrapper = writeLoggingGitWrapper(tempDir, logFile)
        def worktreesRoot = tempDir.resolve('worktrees')

        and: 'N slots, each with its own GitProcessRunner instance, coordinated to start together'
        int slots = 4
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(slots)
        def failures = new ConcurrentLinkedQueue()
        def localTips = new java.util.concurrent.ConcurrentHashMap<String, String>()
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when: 'all slots run their lifecycle concurrently'
        (0..<slots).each { i ->
            def taskId = "PROJ-${i}"
            executor.submit({
                try {
                    start.await()
                    def runner = new GitProcessRunner(gitWrapper.toString())
                    def branchCreator = new TaskBranchCreator(runner)
                    def worktreeManager = new TaskWorktreeManager(runner, worktreesRoot)
                    def push = new BranchPush(runner)

                    def branchName = (branchCreator.createBranch(cloneDir, taskId, null)
                            as BranchCreationResult.Created).branchName()
                    def worktree = worktreeManager.ensureWorktree(cloneDir, taskId, branchName)

                    new File(worktree.toFile(), "${taskId}.txt").text = "work by ${taskId}"
                    runner.run(worktree, 'add', "${taskId}.txt")
                    runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', "round by ${taskId}")
                    localTips[taskId] = runner.run(worktree, 'rev-parse', 'HEAD').stdout().trim()

                    // Exercises a mutating fetch issued with cwd INSIDE the worktree (like
                    // the replica-pair reconciler/TaskBranchLocator do) — the branch is not yet on the
                    // remote at this point, so this is expected to fail harmlessly; the point here
                    // is only that it participates in the same clone's mutation lock without
                    // corrupting anything.
                    def trackingRef = "refs/remotes/origin/${branchName}"
                    runner.run(worktree, 'fetch', 'origin', "${branchName}:${trackingRef}")

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
            def remoteTip = seedRunner.run(bare, 'rev-parse', branchName).stdout().trim()
            remoteTip == localTips[taskId]
        }

        and: 'the mutating git calls this scenario drove were serialized: no two logged intervals overlap'
        !intervalsOverlap(readIntervals(logFile))
    }

    /**
     * A {@code git} stand-in: forwards every call to the real {@code git}, but for the exact
     * repo-level mutating subcommands this task locks (fetch/push/worktree add|remove|prune) it
     * first logs a START line, sleeps briefly to widen any race window, runs the real command, then
     * logs an END line — both timestamped and tagged with its own PID so concurrent invocations
     * never get confused with each other (a PID cannot be reused while its process is still alive).
     */
    private static Path writeLoggingGitWrapper(Path dir, Path logFile) {
        Path script = dir.resolve('logging-git.sh')
        script.toFile().text = """#!/bin/sh
LOG="${logFile}"
SUB1="\$1"
SUB2="\$2"
DELAY=0
case "\$SUB1" in
  fetch|push) DELAY=1 ;;
  worktree)
    case "\$SUB2" in
      add|remove|prune) DELAY=1 ;;
    esac
    ;;
esac
if [ "\$DELAY" = "1" ]; then
  echo "START \$(date +%s.%N) \$\$ \$SUB1 \$SUB2" >> "\$LOG"
  sleep 0.15
fi
git "\$@"
rc=\$?
if [ "\$DELAY" = "1" ]; then
  echo "END \$(date +%s.%N) \$\$ \$SUB1 \$SUB2" >> "\$LOG"
fi
exit \$rc
"""
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString('rwxr-xr-x'))
        script
    }

    private static Map<String, List<Double>> readIntervals(Path logFile) {
        def intervals = [:].withDefault { [] }
        if (!Files.exists(logFile)) {
            return intervals
        }
        logFile.toFile().readLines().each { line ->
            def parts = line.trim().split(/\s+/)
            if (parts.size() >= 3) {
                def kind = parts[0]
                def ts = Double.parseDouble(parts[1])
                def pid = parts[2]
                intervals[pid] << ts
            }
        }
        intervals
    }

    /** True if any two [start, end] intervals (one per logged PID) overlap. */
    private static boolean intervalsOverlap(Map<String, List<Double>> intervalsByPid) {
        def ranges = intervalsByPid.values()
                .findAll { it.size() == 2 }
                .collect { [it.min(), it.max()] }
                .sort { it[0] }
        for (int i = 1; i <ranges.size(); i++) {
            if (ranges[i][0] <ranges[i - 1][1]) {
                return true
            }
        }
        false
    }
}
