package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer
import com.github.oinsio.gnomish.domain.engine.TaskOutcome

/**
 * FR6, FR17, UX2 of add-sandbox-core: {@link ContainerResumeRunner}'s outcome dispatch for the
 * non-dialog outcomes, daemon-free over {@link ContainerResumeSpecBase}'s scripted fixture — the
 * completed report, the paused checkpoint confirmation, and {@code --discard-work} disposal of an
 * interrupted task's kept environment. The escalation dialog lives in
 * {@code ContainerResumeEscalationSpec}.
 */
class ContainerResumeRunnerSpec extends ContainerResumeSpecBase {

    // FR6, UX2: outcome completed (cleanup interrupted, task.json still at the tip) reports the
    // final status summary on stdout — no engine run, no environment.
    def "resuming a completed task prints the final status summary"() {
        given:
        repository.createTask(context('T-COMPLETED'), 'HEAD')
        commitTaskJson('T-COMPLETED', new TaskOutcome.Completed(pipelineEndState()), null)
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')

        when:
        resume('T-COMPLETED', lines(''), System.out)

        then:
        captured.toString('UTF-8').contains('Task: T-COMPLETED')

        cleanup:
        System.out = originalOut
    }

    // FR6, UX2: a paused task resumes through the same checkpoint confirmation as the host path,
    // then drives to Completed.
    def "resuming a paused task confirms the checkpoint and completes"() {
        given:
        repository.createTask(context('T-PAUSED'), 'HEAD')
        repository.recordOutcome('T-PAUSED', new TaskOutcome.Paused(pipelineEndState(), 'build'))
        commitStateAtPipelineEnd('T-PAUSED')
        def consoleOut = new ByteArrayOutputStream()

        when:
        resume('T-PAUSED', lines(''), new PrintStream(consoleOut, true, 'UTF-8'))

        then: 'the checkpoint confirmation was printed through the dialog console'
        consoleOut.toString('UTF-8').contains("Stage 'build' passed. Manual checkpoint reached.")

        and: 'the confirmed continuation drove to the completed outcome'
        taskJsonBelowTip('T-PAUSED').contains('"completed"')
    }

    // FR6: --discard-work on an interrupted task disposes the surviving environment (container,
    // volume, network) so the next materialize seeds a fresh clone, then continues from the branch.
    def "resuming an interrupted task with --discard-work disposes the kept environment"() {
        given: 'an interrupted task (no outcome) whose recorded position is PipelineEnd'
        repository.createTask(context('T-DISC'), 'HEAD')
        commitStateAtPipelineEnd('T-DISC')
        def key = TaskIdSanitizer.sanitize('T-DISC')

        when:
        resume('T-DISC', lines(''), sink(), true)

        then: 'the round key\'s docker objects were force-removed before the drive'
        docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + key
        ])
        docker.runs.contains([
            'volume',
            'rm',
            'gnomish-vol-' + key
        ])
        docker.runs.contains([
            'network',
            'rm',
            'gnomish-net-' + key
        ])

        and: 'the drive still reached the completed outcome'
        taskJsonBelowTip('T-DISC').contains('"completed"')
    }

    // FR6: resumeFromRecordedPosition's AtStage branch — no --discard-work, no pending
    // interrupted verification — leases the environment for the recorded stage AND runs ordinary
    // salvage in-box before the drive continues. Both docker.runs (the leased box's materialize
    // calls) and docker.starts (salvage's in-box "git status --porcelain" probe) are asserted, so
    // a negated-conditional mutant that skips either call is caught. Both calls happen before the
    // drive's own interactive round — which (per ContainerTerminalDriveSpec/
    // ContainerGitModeRunnerSpec) always aborts here, since an interactive round never closes with
    // a snapshot commit — so the abort is expected and does not affect what is being asserted.
    def "resuming an interrupted task at a recorded stage leases the environment and salvages leftovers"() {
        given: 'a freshly created task: no state.json yet, so the recorded position defaults to AtStage(build)'
        repository.createTask(context('T-SALV'), 'HEAD')

        when:
        resume('T-SALV', lines(''), sink())

        then:
        thrown(AbortedException)

        and: 'the round environment for "build" was materialized (a docker run for the round box)'
        docker.runs.any { it.size() > 1 && it[0] == 'run' }

        and: 'salvage ran its leftover probe in-box'
        docker.starts.any { it.contains('git status --porcelain') }
    }

    // FR21, D15: when the branch tip is an interrupted-verification snapshot, the environment is
    // still leased (same-box re-verification needs it) but ordinary salvage does NOT run — the
    // pending verification, not a plain interruption, owns what happens to the round. (The round
    // itself still aborts here for the same reason as the sibling test above.)
    def "resuming a task with a pending interrupted verification leases the environment but skips salvage"() {
        given: 'a task whose branch tip is a snapshot commit, not a plain state commit'
        repository.createTask(context('T-PEND'), 'HEAD')
        commitSnapshotStateAtStage('T-PEND', 'build', 1)

        when:
        resume('T-PEND', lines(''), sink())

        then:
        thrown(AbortedException)

        and: 'the round environment for "build" was still materialized'
        docker.runs.any { it.size() > 1 && it[0] == 'run' }

        and: 'salvage never ran its leftover probe — the pending snapshot owns the round instead'
        !docker.starts.any { it.contains('git status --porcelain') }
    }

    // FR6: resumeFromRecordedPosition's other branch — a recorded position at PipelineEnd (not
    // AtStage) neither leases an environment nor salvages: there is no round in flight to recover.
    // The negated-conditional mutant of the "instanceof Position.AtStage" check would instead
    // materialize a box here — a `docker run` plus an in-box self-check exec — which the
    // no-materialize assertions below catch. The read-only startup orphan sweep (FR11) may still
    // list factory objects; it never materializes or execs, so those listings are tolerated.
    def "resuming an interrupted task already at PipelineEnd touches no environment at all"() {
        given: 'an interrupted task (no outcome) whose recorded position is already PipelineEnd'
        repository.createTask(context('T-NOENV'), 'HEAD')
        commitStateAtPipelineEnd('T-NOENV')

        when:
        resume('T-NOENV', lines(''), sink())

        then: 'no box is materialized (no docker run) and no in-box exec ever ran'
        !docker.runs.any { it.first() == 'run' }
        docker.starts.isEmpty()

        and: 'only the startup orphan sweep ran — read-only listings, no create/remove'
        docker.runs.every {
            it.first() in ['ps', 'volume', 'network'] && it.contains(it.first() == 'ps' ? '-a' : 'ls')
        }

        and: 'the task still completed — no environment was needed to report it'
        taskJsonBelowTip('T-NOENV').contains('"completed"')
    }
}
