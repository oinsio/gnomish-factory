package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.baseref.BaseDecision
import com.github.oinsio.gnomish.baseref.BaseResolution
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * FR6, FR7 of add-git-workflow: the fresh-run task-creation step. Two facts belong to it and
 * nothing else — an absent {@code --base} resolves to the clone's current {@code HEAD} through
 * {@link com.github.oinsio.gnomish.baseref.BaseRefResolver} (FR4, FR10 of
 * add-base-ref-resolution) rather than a hand-rolled default, and a creation failure on a FRESH
 * run is an operator mistake, so it is remapped to a {@link UsageException} (exit code 2)
 * carrying what to do about it rather than propagating as a git-layer fault.
 *
 * <p>{@link GitFreshTaskSupport#createTask} itself never resolves a base: it threads an
 * already-resolved {@link BaseDecision} through to {@link TaskRepository#createTask}, pinning the
 * REAL ref and rule (FR7 of add-base-ref-resolution) rather than re-deriving them — resolution
 * itself is {@link GitFreshTaskSupport#resolveManualBase}'s job, tested separately below.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)); widened by task 6.3 of
 * add-base-ref-resolution.
 */
class GitFreshTaskSupportSpec extends Specification {

    private static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of())

    // FR6, FR7: an already-resolved decision is handed to the port exactly as given — both the ref
    // and the rule that produced it.
    def "passes an already-resolved decision through to the repository unchanged"() {
        given:
        def taskRepository = Mock(TaskRepository)
        def decision = new BaseDecision('release/1.2', BaseRule.EXPLICIT_ARGUMENT, 'explicit --base argument')

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, decision, TaskState.atStageStart('build'))

        then:
        1 * taskRepository.createTask(CONTEXT, 'release/1.2', BaseRule.EXPLICIT_ARGUMENT, _)
    }

    // FR7: only the git-layer failure is remapped. Any other fault propagates unchanged, so a real
    // bug is not disguised as an operator mistake with a misleading exit code.
    def "remaps a creation failure into a usage error naming the task and the way out"() {
        given:
        def taskRepository = Stub(TaskRepository) {
            createTask(_, _, _, _) >> {
                throw new GitTaskRepositoryException('PROJ-1', TaskLifecycleEvent.STARTED, 'branch exists', 'gnomish/PROJ-1')
            }
        }
        def decision = new BaseDecision('HEAD', BaseRule.LOCAL_HEAD, 'the clone\'s local HEAD')

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, decision, TaskState.atStageStart('build'))

        then:
        def ex = thrown(UsageException)
        ex.message.startsWith('could not start git-mode task "PROJ-1"')
        ex.message.contains('branch exists')
        ex.message.contains('--resume')
    }

    // FR7: only the git-layer failure is remapped. Any other fault propagates unchanged.
    def "lets a non-git failure propagate unchanged"() {
        given:
        def boom = new IllegalStateException('boom')
        def taskRepository = Stub(TaskRepository) {
            createTask(_, _, _, _) >> { throw boom }
        }
        def decision = new BaseDecision('HEAD', BaseRule.LOCAL_HEAD, 'the clone\'s local HEAD')

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, decision, TaskState.atStageStart('build'))

        then:
        def thrownEx = thrown(IllegalStateException)
        thrownEx.is(boom)
    }

    // FR6: an explicit --base wins outright.
    def "resolveManualBase resolves an explicit --base as EXPLICIT_ARGUMENT"() {
        expect:
        def decision = GitFreshTaskSupport.resolveManualBase('release/1.2')
        decision.ref() == 'release/1.2'
        decision.rule() == BaseRule.EXPLICIT_ARGUMENT
    }

    // FR6, FR10: an absent --base resolves to the literal "HEAD" via BaseRefResolver, tagged
    // LOCAL_HEAD — the port requires a non-blank baseRef, and MANUAL mode with an absent designator
    // and no configured default falls through to the clone's local HEAD, same as the old
    // hand-rolled default.
    def "resolveManualBase resolves an absent --base to the literal HEAD tagged LOCAL_HEAD"() {
        expect:
        def decision = GitFreshTaskSupport.resolveManualBase(null)
        decision.ref() == 'HEAD'
        decision.rule() == BaseRule.LOCAL_HEAD
    }

    // FR4, FR10: requireResolved is the one place a BaseResolution is unwrapped, so both of its
    // branches are tested here directly — a hand-built Resolved and a hand-built Underdetermined —
    // rather than relying on resolveManualBase's own call path, which can never produce
    // Underdetermined at this call site (MANUAL mode with an absent designator and no configured
    // default always resolves).
    def "requireResolved returns the resolved decision unchanged"() {
        given:
        def decision = new BaseDecision('release/1.2', BaseRule.EXPLICIT_ARGUMENT, 'explicit --base argument')
        def resolution = new BaseResolution.Resolved(decision)

        expect:
        GitFreshTaskSupport.requireResolved(resolution, {
            new IllegalStateException('should not be called')
        }).is(decision)
    }

    // FR4, FR10: an Underdetermined outcome is routed to the caller-supplied exception factory,
    // which names the reason resolution gave up on.
    def "requireResolved throws the exception built from an underdetermined outcome"() {
        given:
        def resolution = new BaseResolution.Underdetermined(
                UnderdeterminedCause.NO_DEFAULT_BRANCH, [], 'no base was named and the repository default branch is unknown')

        when:
        GitFreshTaskSupport.requireResolved(resolution, { underdetermined ->
            new IllegalStateException(underdetermined.reason())
        })

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'no base was named and the repository default branch is unknown'
    }

    // FR4, FR10: unreachableOnManualPath is the exact exception factory resolveManualBase hands to
    // requireResolved — structurally unreachable from resolveManualBase's own call path (MANUAL
    // mode with an absent designator and no configured default never underdetermines), so it is
    // exercised directly here with a hand-built Underdetermined rather than left as untestable dead
    // code.
    def "unreachableOnManualPath names the underdetermined reason in its message"() {
        given:
        def resolution = new BaseResolution.Underdetermined(
                UnderdeterminedCause.DESIGNATOR_CONFLICT, ['a', 'b'], 'the task names more than one base')

        expect:
        def ex = GitFreshTaskSupport.unreachableOnManualPath(resolution)
        ex instanceof IllegalStateException
        ex.message.contains('the task names more than one base')
    }
}
