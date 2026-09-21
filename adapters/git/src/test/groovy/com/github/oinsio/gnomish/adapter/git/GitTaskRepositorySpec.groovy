package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.*
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR1 of add-git-workflow: {@code TaskRepository}'s git realization — create branch + first
 * task.json commit, append decision (resetting outcome), record outcome/escalation, per the
 * ServiceCommitMessages scheme (D14).
 */
class GitTaskRepositorySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
    }

    private static TaskContext sampleContext(String taskId = 'PROJ-1', List<Decision> decisions = []) {
        new TaskContext(taskId, UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body text'), decisions)
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private UntrustedText readTaskJson(String taskId, String ref = 'HEAD') {
        runner.run(worktreeFor(taskId), 'show', "${ref}:.gnomish-task/task.json").stdout()
    }

    def "FR1: createTask creates the branch and commits task.json with the STARTED message"() {
        given:
        def context = sampleContext()

        when:
        repository.createTask(context, TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        then: 'the branch exists in the clone'
        runner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() == 0

        and: 'the worktree carries a commit with the STARTED message'
        def worktree = worktreeFor('PROJ-1')
        def message = runner.run(worktree, 'log', '-1', '--format=%s').stdout().forParsing().trim()
        message == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.STARTED)

        and: 'task.json round-trips the context with null outcome/lastEscalation'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context() == context
        content.outcome() == null
        content.lastEscalation() == null
        content.baseCommit() != null
    }

    // FR2 of harden-logging-observability: the host medium's lifecycle-commit anchor. One INFO
    // line per transition, at the one choke point every transition passes through — so a task's
    // branch-side story is reconstructible from the log without reading the branch.
    def "FR2: each lifecycle transition logs exactly one INFO anchor naming the event"() {
        given:
        def capture = LogCaptureSupport.attach(GitTaskRepository)

        when: 'a task is created and then carried through a second transition'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then: 'one anchor per transition, in order, each naming its event and its task'
        def anchors = capture.list.findAll {
            it.formattedMessage.startsWith('task lifecycle commit written')
        }
        anchors.size() == 2
        anchors.every {
            it.level == Level.INFO && it.formattedMessage.contains('PROJ-1')
        }
        anchors[0].formattedMessage.contains("event=${TaskLifecycleEvent.STARTED}")
        anchors[1].formattedMessage.contains("event=${TaskLifecycleEvent.COMPLETED}")

        cleanup:
        capture.detach()
    }

    // FR3 of harden-task-branch-contract: the STARTED commit carries the initial state.json
    // beside task.json. One commit, not two — a run that dies before its first round completes
    // still leaves a branch the next resume can read, which is the crash loop FR3 closes.
    def "FR3: the STARTED commit carries the initial state.json beside task.json"() {
        when:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        then: 'the STARTED commit itself — not a later one — holds state.json'
        def worktree = worktreeFor('PROJ-1')
        def files = runner.run(worktree, 'show', '--name-only', '--format=', 'HEAD').stdout()
        files.contains('.gnomish-task/task.json')
        files.contains('.gnomish-task/state.json')

        and: 'it records the pipeline\'s first stage with nothing burned yet'
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout()))
        (state.position() as Position.AtStage).name() == 'implement'
        state.attemptsUsed() == 0
        state.attempts().isEmpty()
    }

    // FR4 of harden-task-branch-contract: the decision and the attempt-counter reset it implies
    // are one transition, so they are ONE commit — no tip ever reads "answered, but still
    // exhausted", which a resume would turn straight back into an escalation.
    def "FR4: the decision commit carries the attempt-counter reset"() {
        given: 'a task whose stage burned an attempt before parking, recorded on the branch'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')
        def burned = TaskState.atStageStart('implement').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), []))
        new GitAttemptPersistence(runner, worktree, 'PROJ-1', ClaimEpochSource.NONE).persist('PROJ-1', burned,
                new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0), []))
        def before = runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().forParsing().trim() as Integer

        when: 'the human answer is appended with the reset it implies'
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', Instant.EPOCH),
                burned.resetAttempts())

        then: 'exactly one commit was added'
        def after = runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().forParsing().trim() as Integer
        after == before + 1

        and: 'that one commit carries the decision and the reset counter together'
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1'))).context().decisions().size() == 1
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout()))
        state.attemptsUsed() == 0
        (state.position() as Position.AtStage).name() == 'implement'

        and: 'the tip it replaced still showed the burn — so the reset really landed with the decision'
        def parent = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD~1:.gnomish-task/state.json').stdout()))
        parent.attemptsUsed() == 1
    }

    def "FR2/D7: createTask with an explicit baseRef records that commit as baseCommit"() {
        given: 'a second commit on the clone after the base we want to pin'
        def firstHead = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        new File(cloneDir.toFile(), 'b.txt').text = 'second'
        runner.run(cloneDir, 'add', 'b.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'second')

        when:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, firstHead), TaskStart.pin(firstHead, BaseRule.EXPLICIT_ARGUMENT), TaskState.atStageStart('implement'))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.baseCommit() == firstHead
    }

    // FR7 of add-base-ref-resolution: createTask pins the REAL rule that produced the ref, not a
    // stand-in — the ref and the rule land in the STARTED commit's task.json.
    def "FR7: createTask pins the resolved ref and its rule in the STARTED commit"() {
        when:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.DESIGNATOR), TaskState.atStageStart('implement'))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.pin().ref() == 'HEAD'
        content.pin().rule() == BaseRule.DESIGNATOR
    }

    // FR7: the pin is not re-derived on later lifecycle commits — appendDecision and recordOutcome
    // carry the SAME (ref, rule) forward unchanged, exactly like baseCommit already does.
    def "FR7: the pin survives appendDecision and recordOutcome unchanged"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.DESIGNATOR), TaskState.atStageStart('implement'))

        when:
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null),
                TaskState.atStageStart('implement'))

        then:
        def afterDecision = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        afterDecision.pin().ref() == 'HEAD'
        afterDecision.pin().rule() == BaseRule.DESIGNATOR

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then:
        def afterOutcome = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1', 'HEAD')))
        afterOutcome.pin().ref() == 'HEAD'
        afterOutcome.pin().rule() == BaseRule.DESIGNATOR
    }

    def "FR1: createTask throws when the branch already exists for the taskId"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR5/D9: appendDecision appends to decisions[], resets outcome to null, commits with RESUMED"() {
        given: 'a task parked with a non-null outcome'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when:
        repository.appendDecision('PROJ-1', decision, TaskState.atStageStart('implement'))

        then:
        def worktree = worktreeFor('PROJ-1')
        def message = runner.run(worktree, 'log', '-1', '--format=%s').stdout().forParsing().trim()
        message == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED)

        and:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context().decisions() == [decision]
        content.outcome() == null
    }

    def "FR1: recordOutcome commits the matching message and content for each outcome variant"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'the outcome-recording commit is the tip and carries the expected message'
        def worktree = worktreeFor('PROJ-1')
        commitMessageAt(worktree, 0) == ServiceCommitMessages.taskEvent(expectedEvent)

        and: 'task.json at the tip shows the recorded outcome — including for Completed, whose cleanup is a separate step (FR10 of harden-task-branch-contract)'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1', 'HEAD')))
        expectedKind.isInstance(content.outcome())

        where:
        outcome | expectedEvent | expectedKind
        new TaskOutcome.Completed(TaskState.atStageStart('implement')) | TaskLifecycleEvent.COMPLETED | RecordedOutcome.Completed
        new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement') | TaskLifecycleEvent.PAUSED | RecordedOutcome.Paused
        new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
                    UntrustedText.agent('yes'),
                    UntrustedText.agent('no')
                ])) | TaskLifecycleEvent.ESCALATED | RecordedOutcome.Escalated
        new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
                new AttemptKey('PROJ-1', 'implement', 0), UntrustedText.subprocess('boom')) | TaskLifecycleEvent.ABORTED | RecordedOutcome.Aborted
    }

    private String commitMessageAt(Path worktree, int commitsBack) {
        runner.run(worktree, 'log', "-1", "--skip=${commitsBack}", '--format=%s').stdout().forParsing().trim()
    }

    // FR1, NFR-R2 of type-untrusted-text (design D1, revised 2026-09-17): the flow assertion the
    //     carrier's equality exists for — on the real medium, through the real adapter. The writer
    //     holds a subprocess carrier, the branch holds its bytes, and the reader mints a
    //     branch-document carrier; the two are one value, or every set and map key that spans the
    //     two media stops deduplicating without anything going red.
    def "FR1: an abort cause written to the branch equals the cause read back off it"() {
        given: 'a cause captured from a subprocess, with bytes a sink would have to neutralize'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def written = UntrustedText.subprocess('fatal: \u001B[2K refused\nCaused by: boom')

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Aborted(
                        TaskState.atStageStart('implement'), new AttemptKey('PROJ-1', 'implement', 0), written))

        then: 'what comes back off the branch is the same value, under the branch\'s provenance'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        def readBack = (content.outcome() as RecordedOutcome.Aborted).cause()
        readBack == written
        readBack.hashCode() == written.hashCode()
        readBack.provenance() == Provenance.BRANCH_DOCUMENT

        and: 'the medium carried the bytes, not a rendering of them'
        readBack.forParsing() == written.forParsing()
    }

    def "FR1: recordOutcome for Escalated populates lastEscalation"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ])

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.lastEscalation() == report
    }

    def "FR5: parked and interrupted tasks are distinguishable by outcome, side by side"() {
        given: 'two tasks escalated with a question, both resumed by a decision'
        repository.createTask(sampleContext('PROJ-PARKED'), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.createTask(sampleContext('PROJ-INTERRUPTED'), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ])
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        repository.recordOutcome(
                'PROJ-INTERRUPTED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when: 'the interrupted task is resumed and its process dies mid-stage — no recordOutcome follows'
        repository.appendDecision('PROJ-INTERRUPTED', decision, TaskState.atStageStart('implement'))

        and: 'the parked task is, separately, genuinely parked again (recordOutcome IS called)'
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'verify'))

        then: 'the interrupted task.json shows outcome null — process death is indistinguishable from "still working" by design'
        def interrupted = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-INTERRUPTED')))
        interrupted.outcome() == null

        and: 'the parked task.json shows its recorded outcome'
        def parked = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-PARKED')))
        parked.outcome() instanceof RecordedOutcome.Paused

        and: 'both preserve lastEscalation from the earlier escalation — kept separately from outcome (FR5)'
        interrupted.lastEscalation() == report
        parked.lastEscalation() == report
    }

    // FR10 of harden-task-branch-contract: the cleanup commit is the destructive last step of the
    // completion sequence, so recordOutcome leaves the envelope in place for the tracker write to
    // follow — the CompletedUncleaned shape a kill in that window freezes.
    def "FR10: recordOutcome(Completed) leaves the envelope at the tip for finishCleanup to remove"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then: 'the tip still carries the envelope, recording Completed'
        def worktree = worktreeFor('PROJ-1')
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() != ''
        commitMessageAt(worktree, 0) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)
    }

    // FR10 of harden-task-branch-contract: running the destructive step twice equals running it once.
    // FR5 of harden-logging-observability: the second run writes nothing, so only a DEBUG line
    // distinguishes "already done" from "never ran".
    def "FR10: finishCleanup on an already-cleaned tip changes nothing"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        def worktree = worktreeFor('PROJ-1')
        def tip = runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        def logs = LogCaptureSupport.attach(CleanupCommit, Level.DEBUG)

        when:
        repository.finishCleanup('PROJ-1')
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim() == tip

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('cleanup commit for task PROJ-1 is a no-op')
    }

    // FR3 of fix-envelope-medium: the cleanup guard asks the tip, not the worktree, so the step
    // converges from every state its own two-command sequence can freeze. A predecessor killed
    // between `git rm -r` and the commit leaves the removal staged in the worktree over a tip that
    // still carries the envelope; the next pickup on that same worktree must land the removal
    // rather than mistake a dirty worktree for a cleaned branch.
    def "FR3: finishCleanup converges a removal a killed predecessor already staged"() {
        given: 'a completed task whose tip still carries the envelope'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        def worktree = worktreeFor('PROJ-1')

        and: 'a predecessor staged the removal and died before committing it'
        runner.run(worktree, 'rm', '-r', '.gnomish-task')
        assert runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() != ''
        def logs = LogCaptureSupport.attach(CleanupCommit, Level.INFO)

        when:
        repository.finishCleanup('PROJ-1')
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the staged removal landed: the tip no longer carries the envelope'
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() == ''

        and: 'NFR-O1: one INFO line tells the operator a converged crash apart from a first run'
        events.any {
            it.level == Level.INFO &&
            it.formattedMessage == 'cleanup commit for task PROJ-1 lands a removal a predecessor already staged'
        }

        and: 'the cleanup commit is the tip'
        commitMessageAt(worktree, 0) == ServiceCommitMessages.cleanup()

        when: 'the recovery runs a second time'
        def cleanedTip = runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        repository.finishCleanup('PROJ-1')

        then: 'it is a no-op — the tip id does not move'
        runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim() == cleanedTip
    }

    // NFR-O1 of fix-envelope-medium: the converged-crash line is what tells an operator a recovery
    // apart from a first run, so an ordinary cleanup — the removal landing from a live worktree —
    // must stay silent. A line on every cleanup would say "a predecessor died here" every time.
    def "NFR-O1: an ordinary cleanup does not claim a predecessor staged its removal"() {
        given: 'a completed task whose worktree still holds the envelope'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        def logs = LogCaptureSupport.attach(CleanupCommit, Level.INFO)

        when:
        repository.finishCleanup('PROJ-1')
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the removal landed, and only the lifecycle anchor was said about it'
        runner.run(worktreeFor('PROJ-1'), 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() == ''
        events.every {
            !it.formattedMessage.contains('a predecessor already staged')
        }
    }

    def "FR15/M4: finishCleanup adds the cleanup commit removing .gnomish-task/ from the tip, full history preserved"() {
        given: 'a task with at least one round commit before completion, to prove earlier history stays reachable'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null), TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')
        def commitCountBeforeCompletion = commitCount(worktree)

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')

        then: 'the tip has no .gnomish-task/ directory on disk'
        !new File(worktree.toFile(), '.gnomish-task').exists()

        and: 'the tip has no .gnomish-task/ directory in the git tree either'
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() == ''

        and: 'the last commit is the cleanup commit'
        commitMessageAt(worktree, 0) == ServiceCommitMessages.cleanup()

        and: 'the second-to-last commit is the COMPLETED outcome commit'
        commitMessageAt(worktree, 1) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)

        and: 'the completed task.json is still readable from the second-to-last commit — history preserved'
        def completedSha = runner.run(worktree, 'log', '-1', '--skip=1', '--format=%H').stdout().forParsing().trim()
        def historicalJson = runner.run(worktree, 'show', "${completedSha}:.gnomish-task/task.json").stdout()
        def historicalContent = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(historicalJson))
        historicalContent.outcome() instanceof RecordedOutcome.Completed

        and: 'every earlier round/lifecycle commit is still reachable — two extra commits appended on top'
        commitCount(worktree) == commitCountBeforeCompletion + 2
    }

    def "FR15/M4: recordOutcome for non-Completed outcomes never adds a cleanup commit, .gnomish-task/ stays"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: '.gnomish-task/ is still present on disk and at HEAD'
        new File(worktree.toFile(), '.gnomish-task').exists()
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() != ''

        and: 'no commit carries the cleanup message'
        allCommitMessages(worktree).every {
            it != ServiceCommitMessages.cleanup()
        }

        where:
        outcome << [
            new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'),
            new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
            new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
                UntrustedText.agent('yes'),
                UntrustedText.agent('no')
            ])),
            new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
            new AttemptKey('PROJ-1', 'implement', 0), UntrustedText.subprocess('boom')),
        ]
    }

    // FR10, D10 of add-claim-heartbeat: recording a terminal PARK sets the durable "tracker-write
    // pending" marker before its (git-unfenced) tracker write, so a resuming instance can tell an
    // orphaned park from a settled one.
    def "FR10: recordOutcome for a park (#event) sets the tracker-write pending marker, round-tripping the branch"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'a fresh read of the branch tip sees the pending marker'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.trackerWritePending()

        where:
        event | outcome
        'ESCALATED' | new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
                    UntrustedText.agent('yes'),
                    UntrustedText.agent('no')
                ]))
        'PAUSED' | new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement')
    }

    // FR10, D10: a non-park terminal outcome never sets the marker (Aborted's write is best-effort;
    // Completed's reconcile is decided by cleanup-detection, not the marker).
    def "FR10: recordOutcome for Aborted leaves the tracker-write pending marker unset"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome(
                'PROJ-1',
                new TaskOutcome.Aborted(TaskState.atStageStart('implement'), new AttemptKey('PROJ-1', 'implement', 0),
                UntrustedText.subprocess('boom')))

        then:
        !TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1'))).trackerWritePending()
    }

    // FR10, D10: once the park's tracker write confirms, confirmTerminalWrite clears the marker in a
    // new commit, preserving the recorded outcome and escalation.
    def "FR10: confirmTerminalWrite clears the pending marker while preserving the recorded park outcome"() {
        given:
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ])
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        when:
        repository.confirmTerminalWrite('PROJ-1')

        then: 'the marker is cleared'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        !content.trackerWritePending()

        and: 'the recorded outcome and escalation survive the clear'
        content.outcome() != null
        content.lastEscalation() == report

        and: 'the clear is a dedicated write-confirmed commit at the tip'
        def worktree = worktreeFor('PROJ-1')
        runner.run(worktree, 'log', '-1', '--format=%s').stdout().forParsing().trim() ==
                ServiceCommitMessages.trackerWriteConfirmed()
    }

    // FR5, D8 of fix-denial-attribution-durability: host mode has no egress guard and so mints no
    //     position of its own — but a branch whose earlier rounds ran in a box carries one, and a
    //     host-side lifecycle rewrite must not be what erases it (the mirrored half of task 4.7)
    def "FR5: host lifecycle rewrites carry both envelopes' committed cursors forward"() {
        given: 'a task branch whose envelopes carry the positions a container-mode run committed'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        stampCursors('PROJ-1',
                new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z'),
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z'))

        when: 'a park and the resume that answers it both rewrite the envelopes'
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        repository.confirmTerminalWrite('PROJ-1')
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null),
                TaskState.atStageStart('implement'))

        then: 'both positions are still at the tip for the next run to be offered'
        TaskJsonMapper.readDto(readTaskJson('PROJ-1')).egressCursor() ==
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z')
        StateJsonMapper.readDto(
                runner.run(worktreeFor('PROJ-1'), 'show', 'HEAD:.gnomish-task/state.json').stdout())
                .egressCursor() == new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z')
    }

    // FR2 of fix-envelope-medium: the three read-modify-write sites take their input from the
    //     branch tip, so a worktree file a killed predecessor left dirty — a staged half-write, an
    //     edit whose commit never came — can never be carried forward into a lifecycle commit. Each
    //     scenario below drives one site over a worktree whose file disagrees with the tip.
    def "FR2: the decision rewrite carries the tip's DTO forward, never the worktree's"() {
        given: 'a branch whose tip carries a container-mode position in task.json'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        stampCursors('PROJ-1',
                new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z'),
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z'))

        and: 'an uncommitted worktree edit naming another position'
        smudgeTaskJson('PROJ-1', new EgressCursorDto('sha256:from-disk', '2026-09-21T00:00:00Z'))

        when:
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null),
                TaskState.atStageStart('implement'))

        then: 'the decision commit carries what the branch recorded; the disk edit decided nothing'
        TaskJsonMapper.readDto(readTaskJson('PROJ-1')).egressCursor() ==
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z')
    }

    def "FR2: the terminal-write receipt clears the tip's DTO, never the worktree's"() {
        given: 'a park whose tip carries a container-mode position beside the pending marker'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        stampCursors('PROJ-1',
                new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z'),
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))

        and: 'an uncommitted worktree edit naming another position'
        smudgeTaskJson('PROJ-1', new EgressCursorDto('sha256:from-disk', '2026-09-21T00:00:00Z'))

        when:
        repository.confirmTerminalWrite('PROJ-1')

        then: 'the receipt commit preserves every field of the tip, and clears only the marker'
        def committed = TaskJsonMapper.readDto(readTaskJson('PROJ-1'))
        committed.egressCursor() == new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z')
        !TaskJsonMapper.fromDto(committed).trackerWritePending()
    }

    def "FR2: the cursor carry-forward reads the tip's state.json, never the worktree's"() {
        given: 'a branch whose tip carries a container-mode position in state.json'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        stampCursors('PROJ-1',
                new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z'),
                new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z'))

        and: 'an uncommitted worktree edit naming another position'
        Path stateJson = worktreeFor('PROJ-1').resolve('.gnomish-task').resolve('state.json')
        def onDisk = StateJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(stateJson)))
        Files.writeString(stateJson, TaskStateJson.mapper().writeValueAsString(new StateJsonDto(
                        onDisk.version(), onDisk.position(), onDisk.attemptsUsed(), onDisk.attempts(), onDisk.totals(),
                        new EgressCursorDto('sha256:from-disk', '2026-09-21T00:00:00Z'))))

        when: 'a lifecycle rewrite regenerates state.json'
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null),
                TaskState.atStageStart('implement'))

        then: 'the regenerated file carries the position the branch recorded'
        StateJsonMapper.readDto(
                runner.run(worktreeFor('PROJ-1'), 'show', 'HEAD:.gnomish-task/state.json').stdout())
                .egressCursor() == new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z')
    }

    // FR2 of fix-envelope-medium: every transition that rewrites the envelope runs on a branch that
    //     carries one, so a tip without it is a fault — reported exactly as the worktree read's I/O
    //     failure was, never absorbed into a rewrite over a fabricated DTO. A cleaned tip is the one
    //     state that reaches these calls with no envelope, and it is reachable only by a caller
    //     driving a lifecycle write after the completion cleanup.
    def "FR2: #transition fails loudly when the tip carries no task envelope"() {
        given: 'a branch whose cleanup commit took the envelope off the tip'
        repository.createTask(sampleContext(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        assert runner.run(worktreeFor('PROJ-1'), 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().forParsing().trim() == ''

        when:
        rewrite.call(repository)

        then:
        def e = thrown(GitTaskRepositoryException)
        e.message.contains('task.json')

        where:
        transition | rewrite
        'the decision rewrite' | { GitTaskRepository it ->
            it.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null),
            TaskState.atStageStart('implement'))
        }
        'the outcome rewrite' | { GitTaskRepository it ->
            it.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        }
        'the write receipt' | { GitTaskRepository it ->
            it.confirmTerminalWrite('PROJ-1')
        }
    }

    /** Rewrites the worktree's {@code task.json} with another position, leaving it uncommitted. */
    private void smudgeTaskJson(String taskId, EgressCursorDto cursor) {
        Path taskJson = worktreeFor(taskId).resolve('.gnomish-task').resolve('task.json')
        def onDisk = TaskJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(taskJson)))
        Files.writeString(taskJson, TaskStateJson.mapper().writeValueAsString(onDisk.withEgressCursor(cursor)))
    }

    /**
     * Writes an attempt-side cursor into {@code state.json} and an escalation-side one into
     * {@code task.json}, as a container-mode run would have left them, and commits both.
     */
    private void stampCursors(String taskId, EgressCursorDto attempt, EgressCursorDto escalation) {
        Path worktree = worktreeFor(taskId)
        Path stateJson = worktree.resolve('.gnomish-task').resolve('state.json')
        Path taskJson = worktree.resolve('.gnomish-task').resolve('task.json')
        def state = StateJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(stateJson)))
        Files.writeString(stateJson, TaskStateJson.mapper().writeValueAsString(new StateJsonDto(
                        state.version(), state.position(), state.attemptsUsed(), state.attempts(), state.totals(), attempt)))
        Files.writeString(taskJson, TaskStateJson.mapper()
                .writeValueAsString(TaskJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(taskJson))).withEgressCursor(escalation)))
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'round state')
    }

    private int commitCount(Path worktree) {
        runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().forParsing().trim() as int
    }

    private List<String> allCommitMessages(Path worktree) {
        runner.run(worktree, 'log', '--format=%s').stdout().forParsing().readLines()
    }
}
