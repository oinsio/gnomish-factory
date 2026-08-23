package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * The bare-origin clone, task branch, and log capture the lifecycle-decorator specs of
 * fix-lifecycle-push share: a clone of a real bare origin, a task branch that can be advanced one
 * commit at a time exactly as a lifecycle write advances it, and the {@code LifecyclePush} logger's
 * events. Extracted so the four specs over the two decorators (the {@code TaskRepository} pair and
 * the {@code TaskLifecycleStore} one) state only what they assert.
 *
 * <p>A plain fixture trait, not a reusable port abstraction — split out for the file-size guidance
 * of `.claude/rules/process-invariants.md` and to keep one copy of the setup.
 */
trait LifecyclePushFixture implements BareGitRepoFixture {

    static final String TASK_ID = 'T-1'
    static final String BRANCH = 'gnomish/T-1'

    final GitProcessRunner git = new GitProcessRunner()

    Path origin
    Path cloneDir

    abstract Path getTempDir()

    /** Clones a fresh bare origin and lays down the task branch the decorators push. */
    void initLifecyclePushFixture() {
        origin = initBareRepo(tempDir, 'origin.git')
        cloneDir = tempDir.resolve('clone')
        git.run(tempDir, 'clone', '-q', origin.toString(), cloneDir.toString())
        Files.writeString(cloneDir.resolve('a.txt'), 'a')
        commitAll(cloneDir, 'init')
        git.run(cloneDir, 'branch', BRANCH)
    }

    /** Advances the task branch by one commit, as a real lifecycle write would. */
    String commitOnTaskBranch(String message) {
        def worktree = tempDir.resolve("wt-${message}")
        git.run(cloneDir, 'worktree', 'add', '-q', worktree.toString(), BRANCH)
        Files.writeString(worktree.resolve("${message}.txt"), message)
        commitAll(worktree, message)
        def sha = gitOutput(worktree, 'rev-parse', 'HEAD')
        git.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        sha
    }

    /** The commit {@code origin} currently holds the task branch at, if any. */
    Optional<String> remoteTip() {
        new RemoteBranchTip(git).read(cloneDir, BRANCH)
    }

    /** The {@code LifecyclePush} log events emitted while {@code emit} runs. */
    static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LifecyclePush)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        appender.list
    }
}
