package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import java.nio.file.Files

/**
 * F135, FR13, NFR-S2 of tighten-spec-contract (design D13): the take's loaded-branch routes decide
 * on the registered commit, so a terminal outcome or a gate the gnome commits above the register
 * routes nothing. The branch is written through the production lifecycle store (every closing
 * commit registered) and the gnome's commit lands on the task branch alone, as a mid-round push
 * leaves it; the pickup then routes over the real repository.
 *
 * <p>The run continues from the registered commit's own shape — an ordinary round under a manual
 * stage, ending at its gate — which is how each feature tells "routed by the forgery" from "routed
 * by the record": a forged {@code Completed} would finish with zero rounds, a forged gate would
 * write an approval.
 */
class RegisteredTakeRouteSpec extends TakeResumeSpecBase {

    private static final String TASK = 'PROJ-1'

    def setup() {
        repository().createTask(context(TASK), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(TASK, TaskState.atStageStart('build'))
    }

    private TakeLoadedBranchRoutes<ResumeBootstrap> loadedRoutes() {
        def mechanics = new HostResumeMechanics(newTakeResumeRunner(), taskGit, registeredClone, pipeline(AdvancementMode.MANUAL))
        new TakeLoadedBranchRoutes<>(mechanics, new TakeDecisionResume<>(mechanics), taskGit,
                VirtualTimeRetries.terminalWrite())
    }

    /** The gnome rewrites one envelope file in its worktree and commits it; the register stays. */
    private void gnomeRewrites(String file, Closure<?> edit) {
        def path = expectedWorktree(TASK).resolve('.gnomish-task').resolve(file)
        def json = new ObjectMapper().readTree(path.toFile()) as ObjectNode
        edit.call(json)
        Files.writeString(path, new ObjectMapper().writeValueAsString(json))
        commitAll(expectedWorktree(TASK), 'wip: progress')
    }

    private List<String> subjects() {
        gitOutput(cloneDir, 'log', '--format=%s', "gnomish/${TASK}").readLines()
    }

    // F135: the forged outcome is not the record the routes read — no finish, no cleanup, and the
    //     run goes on from the registered round to its gate, as if the gnome had written nothing.
    // Scenario: lifecycle/claim-heartbeat / A gnome-written terminal outcome is not reconciled
    // Scenario: lifecycle/task-branch-contract / A gnome-written Completed outcome is not a pending cleanup
    def "F135: a Completed outcome the gnome committed above the register posts no finish and writes no cleanup"() {
        given:
        gnomeRewrites('task.json') { ObjectNode it ->
            it.putObject('outcome').put('type', 'completed')
        }

        when:
        def result = loadedRoutes().route(resumeOrder(pipeline(AdvancementMode.MANUAL), TASK))

        then: 'the registered shape ran: a round, then the manual stage\'s gate park'
        0 * tracker.finish(*_)
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        result instanceof TakeResult.AwaitingHuman

        and: 'no cleanup commit was written'
        !subjects().contains('gnomish: cleanup')
    }

    // F135: a gate the gnome writes — the gate position under a paused outcome — opens nothing: no
    //     approval write, and the run goes on from the registered round.
    // Scenario: lifecycle/task-branch-contract / A gnome-written gate position opens no gate
    def "F135: a gate position the gnome committed above the register performs no approval write"() {
        given:
        gnomeRewrites('state.json') { ObjectNode it ->
            it.putObject('position').put('type', 'awaitingApproval').put('stage', 'build')
        }
        gnomeRewrites('task.json') { ObjectNode it ->
            it.putObject('outcome').put('type', 'paused').put('passedStage', 'build')
        }

        when:
        def result = loadedRoutes().route(resumeOrder(pipeline(AdvancementMode.MANUAL), TASK))

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        result instanceof TakeResult.AwaitingHuman

        and:
        !subjects().contains('gnomish: task approved')
    }
}
