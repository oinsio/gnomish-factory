package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * Task 5.3 of migrate-verify-to-executor-contract (FR3, FR11, NFR-R1; design D11 as amended
 * 2026-11-14, D14, D21), the container kill-point matrix of task 4.3b with a pollable {@code program}
 * verdict check, picked up on another clone ({@link PendingPollOtherCloneE2ESpecBase}): a production
 * container {@code run} binds the law's program check through a real fresh box per request, dies at a
 * kill window, and {@code run --resume} over a fresh clone of the Gitea origin continues it. The program
 * ({@link FakeAgentSandboxImage#ensureBuiltWithVerdictProgram}) keeps its run in its keys, which name
 * the {@code HEAD} of the box each request ran in — so the keys the pending commits record show every
 * request, the dying instance's {@code start} and the pickup's polls, on the round's attempt commit, and
 * no pending commit above the recorded one names a {@code start}. W2 adopts the start's key and polls it
 * into a rotated key distinguishable from the first (W3's rotation, here on the resume path), then polls
 * that; W4 re-polls the ended run's key. Each row ends with a second recovery pass that changes nothing.
 * Docker- and guard-image-gated.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class PendingPollProgramOtherCloneContainerE2ESpec extends PendingPollOtherCloneE2ESpecBase {

    @Override
    SandboxProperties sandbox() {
        new SandboxProperties(FakeAgentSandboxImage.ensureBuiltWithVerdictProgram(), null, null, null, [], false, null, null, null, null)
    }

    static PipelineDefinition law() {
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'judge-bot', 'judge', [:], Duration.ofSeconds(1),
        Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, [])
        def stage = new StageDefinition('work', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [check], new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(1), [stage], null, [
            new ExecutorDeclaration('judge-bot', ExecutorDeclaration.Type.PROGRAM, [
                'sh',
                FakeAgentSandboxImage.VERDICT_PROGRAM
            ], [:], [], [])
        ])
    }

    /** The keys the pending commits of {@code repo} above {@code below} record, oldest first. */
    List<String> keysAbove(java.nio.file.Path repo, String below) {
        gitOutput(repo, 'rev-list', '--reverse', "${below}..${branch()}").readLines().findAll {
            subjectOf(repo, it).startsWith('gnomish: pending')
        }.collect {
            stateAt(repo, it).pendingPolls()*.key()*.value().join(',')
        }
    }

    // Scenario: verification/verdict-executors / A resumed poll runs in a box at the same commit
    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // Scenario: verification/pending-poll / Kill after the terminal poll re-polls the persisted key
    def "#window on another clone: the program check's recorded key is polled in new boxes at the attempt commit, and nothing starts"() {
        given:
        taskId = "PEND-P-${System.nanoTime() % 100000}"
        def pipeline = law()

        when:
        freshRunOver(pipeline) { PendingKills.killedAt(point, it) }

        then: 'the dying instance\'s start ran in a box at the attempt commit, and its key reached origin'
        thrown(RunKills.SimulatedKill)
        def recorded = commitOf(cloneDir, registerRef(taskId))
        remote(registerRef(taskId)) == recorded
        def snapshot = newestWith(cloneDir, taskId, 'gnomish: snapshot work#0')
        keysAbove(cloneDir, snapshot) == dyingKeys.collect { it + snapshot }

        when: 'a second instance on another clone resumes the task'
        def other = otherClone()
        resume(pipeline, other)

        then: 'only polls: the pending commits above the recorded one name poll keys at the attempt commit, none a start'
        keysAbove(other, recorded) == pickupKeys.collect { it + snapshot }

        and: 'the round closed passing on the last of them'
        closedPassing(other, newestWith(other, taskId, 'gnomish: round work#0'))

        and:
        secondPassIsNoOp(pipeline, other)

        where:
        window | point | dyingKeys | pickupKeys
        'W2' | PendingKills.Point.AFTER_PENDING_COMMIT | ['start-'] | ['poll-']
        'W4' | PendingKills.Point.AFTER_TERMINAL_POLL | ['start-', 'poll-'] | []
    }
}
