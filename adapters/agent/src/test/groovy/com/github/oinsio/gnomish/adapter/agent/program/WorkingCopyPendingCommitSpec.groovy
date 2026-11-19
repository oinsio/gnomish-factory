package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, "The lag the write leaves behind", as
 * amended by review r2 of 2026-11-12); NFR-S4 of define-executor-contract: the working-copy check of
 * a {@code verdict} run across the factory's own pending commit, over a real git working copy. The
 * factory's pending commit is landed the way both media land it — the branch, and so {@code HEAD},
 * moves to a commit whose tree is the registered one with {@code state.json} replaced, while the
 * index and the working tree stay — and the run's own changes are made between the two views.
 */
class WorkingCopyPendingCommitSpec extends Specification implements LocalGitRepoFixture {

    static final String STATE = '.gnomish-task/state.json'
    static final String BRANCH = 'gnomish/TASK-7'

    @TempDir
    Path tempDir

    Path repo
    HostTaskExecutionEnvironment environment
    /** The round's last registered commit, as the run's cell holds it. */
    String registered
    LastRegisteredCommit cell = [
        lastRegistered: { -> Optional.ofNullable(registered) },
        registeredChain: { -> [] }
    ] as LastRegisteredCommit
    int landed

    def setup() {
        repo = initWorkingRepo(tempDir)
        Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
        Files.createDirectories(repo.resolve('.gnomish-task'))
        Files.writeString(repo.resolve(STATE), '{"round":0}')
        commitAll(repo, 'gnomish: snapshot implement#1 token')
        environment = new HostTaskExecutionEnvironment(
                repo, new VirtualClock(), TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        environment.materialize(BRANCH, null)
        registered = gitOutput(repo, 'rev-parse', 'HEAD')
    }

    def cleanup() {
        environment.dispose()
    }

    private WorkingCopyCheck check() {
        new WorkingCopyCheck(environment, Duration.ofSeconds(30), new VirtualClock(), cell)
    }

    private WorkingCopyView.Taken before() {
        (WorkingCopyView.Taken) check().view()
    }

    private ExecutorResult judge(WorkingCopyView.Taken before) {
        def verdict = new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(),
                RoleInputs.verdict('criteria'), JsonNodeFactory.instance.objectNode(), ProgramBounds.DEFAULTS.hints(), [], null)
        def answer = JsonNodeFactory.instance.objectNode().put('passed', true)
        check().judge('acme', verdict, before, new ExecutorResult(Status.COMPLETED, null, null, null, answer,
                Usage.absent(), []))
    }

    /**
     * The factory lands a pending commit: built in a side clone on the registered commit with only
     * {@code state.json} replaced, then the branch moves to it under a compare-and-swap on the
     * registered commit — the index and the working tree are not touched — and the cell records it.
     */
    private void factoryPending() {
        def side = tempDir.resolve("side-${landed++}")
        gitOutput(tempDir, 'clone', '-q', '--no-hardlinks', repo.toString(), side.toString())
        gitOutput(side, 'checkout', '-q', '--detach', registered)
        Files.writeString(side.resolve(STATE), "{\"pending\":${landed}}")
        commitAll(side, 'gnomish: pending implement#1 token')
        def pending = gitOutput(side, 'rev-parse', 'HEAD')
        gitOutput(repo, 'fetch', '-q', side.toString(), pending)
        gitOutput(repo, 'update-ref', gitOutput(repo, 'symbolic-ref', 'HEAD'), pending, registered)
        registered = pending
    }

    // Scenario: executor/wire-contract / A pending commit does not refuse the run's answer
    def "FR20: a verdict run across which the factory landed #count pending commit(s) keeps its answer"() {
        given:
        def view = before()

        when:
        count.times { factoryPending() }
        def result = judge(view)

        then:
        result.status() == Status.COMPLETED
        result.answer() != null

        where:
        count << [1, 2]
    }

    // Scenario: executor/wire-contract / A pending commit does not refuse the run's answer
    def "FR20: a second pending check's run, begun on the first pending commit's lag, keeps its answer"() {
        given: 'the first pending commit landed before the run, its lag in the view before'
        factoryPending()
        def view = before()

        when:
        factoryPending()
        def result = judge(view)

        then:
        result.status() == Status.COMPLETED
    }

    // Scenario: executor/wire-contract / A run's own change is still refused across a pending commit
    def "FR20: a run that #change across a pending commit is refused naming #named"() {
        given:
        def view = before()

        when:
        factoryPending()
        edit.call(this)
        def result = judge(view)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.answer() == null
        result.reason().contains('the run changed ' + named)

        where:
        change | named | edit
        'edits state.json' | STATE | { s ->
            Files.writeString(s.repo.resolve(STATE), '{"forged":true}')
        }
        'stages an edit of state.json' | STATE | { s ->
            Files.writeString(s.repo.resolve(STATE), '{"forged":true}'); s.gitOutput(s.repo, 'add', STATE)
        }
        'edits another path' | 'tracked.txt' | { s ->
            Files.writeString(s.repo.resolve('tracked.txt'), 'edited\n')
        }
        'commits on top of it' | 'branch.oid' | { s ->
            Files.writeString(s.repo.resolve('tracked.txt'), 'edited\n'); s.commitAll(s.repo, 'run commit')
        }
    }

    // Scenario: executor/wire-contract / A run's own change is still refused across a pending commit
    def "FR20: a state.json edit staged before the factory's commit lands is refused"() {
        given: 'the run stages an edit of state.json, then the factory lands its pending commit'
        def view = before()
        Files.writeString(repo.resolve(STATE), '{"forged":true}')
        gitOutput(repo, 'add', STATE)
        Files.writeString(repo.resolve(STATE), '{"round":0}')
        factoryPending()

        when:
        def result = judge(view)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the run changed ' + STATE)
    }

    // Scenario: executor/wire-contract / An empty commit of the run is refused
    def "FR20: an empty commit of the run is refused naming HEAD #moment"() {
        given:
        def view = before()

        when:
        if (pendingFirst) {
            factoryPending()
        }
        gitOutput(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-q', '--allow-empty', '-m', kind)
        if (kind == 'edit and revert') {
            Files.writeString(repo.resolve('tracked.txt'), 'edited\n')
            commitAll(repo, 'edit')
            Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
            commitAll(repo, 'revert')
        }
        def result = judge(view)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the run changed branch.oid ')

        where:
        moment | pendingFirst | kind
        'with no pending commit landed during the run' | false | 'empty'
        'above a pending commit landed during the run' | true | 'empty'
        'when it is an edit and its revert, above a pending commit' | true | 'edit and revert'
    }

    // Scenario: executor/wire-contract / An empty commit of the run is refused
    def "FR20: a commit of the run that lands before the factory's pending commit leaves HEAD off the registered commit"() {
        given:
        def view = before()
        def snapshot = registered
        gitOutput(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-q', '--allow-empty', '-m', 'empty')

        when: 'the factory\'s compare-and-swap on the registered commit refuses, so nothing is registered'
        registered = snapshot
        def result = judge(view)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the run changed branch.oid ')
    }

    // NFR-S4: a round with nothing registered has no factory move
    def "FR20: with no registered commit any move of HEAD is refused, the factory's own included"() {
        given:
        def view = before()
        factoryPending()
        registered = null

        when:
        def result = judge(view)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('branch.oid ')
    }
}
