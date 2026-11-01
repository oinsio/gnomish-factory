package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of harden-task-branch-contract: a history read that never ran to its own exit established
 * nothing about the branch, so it must not feed the usage report — the seam rule
 * {@code GitShowTipTerminationSpec} pins for the tip reader, applied to the walker. An interrupted
 * {@code git log} hands back a prefix of the commit list, which reads as a complete report silently
 * missing its newest rounds; an interrupted {@code git show} reads as the absent-state case and
 * silently drops a commit's rounds.
 *
 * <p>Driven through the runner's port: a scripted git ({@link ScriptedGit}) that answers every
 * command before the one under test and blocks on that one until its thread is interrupted (design
 * D27 of supervise-daemon-loops-and-embed-dashboard).
 */
class UsageHistoryWalkerTerminationSpec extends Specification {

    /** The task.json the envelope revision answers with: the walk reaches its listing past it. */
    private static final String TASK_JSON = '{"version":1,"taskId":"PROJ-1","title":"T","body":"B",' +
    '"createdAt":"2026-07-18T09:00:00Z","baseCommit":"' + 'b' * 40 + '","decisions":[]}'

    /** The state.json the envelope revision answers with: the tip's recorded totals, empty. */
    private static final String STATE_JSON = TaskStateJson.mapper().writeValueAsString(
    StateJsonMapper.toDto(TaskState.atStageStart('build')))

    /** The one commit the history listing names, apart from the registered commit. */
    private static final String LISTED = 'c' * 40

    @TempDir
    Path tempDir

    def "FR16: an interrupted history listing is unavailability, never an empty report"() {
        when:
        def thrown = interruptDuring(stallingOn(['log']))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR16: an interrupted state read is unavailability, never a skipped commit"() {
        when:
        def thrown = interruptDuring(stallingOn([
            'show',
            LISTED + ':.gnomish-task/state.json'
        ]))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    /** Runs a walk over {@code stalling.git} in its own thread and interrupts it mid-stall. */
    private Throwable interruptDuring(Map stalling) {
        ScriptedGit git = stalling.git
        Throwable thrown = null
        def walker = new Thread({
            try {
                new UsageHistoryWalker(git, VirtualTimeGitRetries.gitInfrastructure()).walk(tempDir, 'PROJ-1')
            } catch (Throwable t) {
                thrown = t
            }
        })
        walker.start()
        git.awaitCall(stalling.command, walker)
        walker.interrupt()
        walker.join(Duration.ofSeconds(30).toMillis())
        return thrown
    }

    /**
     * A git that lets the locator find a local branch and the walk reach {@code stalled} — every
     * command before it answers — and then stalls until interrupted, the outcome no real repository
     * can be asked to produce on demand.
     */
    private static Map stallingOn(List<String> stalled) {
        def git = new ScriptedGit()
                .stalls(stalled, new CountDownLatch(1))
                .answer([
                    'show',
                    '*:.gnomish-task/task.json'
                ], 0, TASK_JSON)
                .answer([
                    'show',
                    'a' * 40 + ':.gnomish-task/state.json'
                ], 0, STATE_JSON)
                .answer(['log'], 0, LISTED + '\n')
                .answer(['rev-parse'], 0, 'a' * 40 + '\n')
                .answer([], 0)
        [git: git, command: stalled]
    }
}
