package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import spock.lang.Specification

/**
 * FR6, FR7 of add-git-workflow: the fresh-run task-creation step. Two facts belong to it and
 * nothing else — an absent {@code --base} means the clone's current {@code HEAD} (passed through
 * literally, since the port requires a non-blank baseRef), and a creation failure on a FRESH run
 * is an operator mistake, so it is remapped to a {@link UsageException} (exit code 2) carrying
 * what to do about it rather than propagating as a git-layer fault.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)).
 */
class GitFreshTaskSupportSpec extends Specification {

    private static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of())

    // FR6: an explicit --base is handed to the port exactly as the operator gave it.
    def "passes an explicit base ref through to the repository unchanged"() {
        given:
        def taskRepository = Mock(TaskRepository)

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, 'release/1.2')

        then:
        1 * taskRepository.createTask(CONTEXT, 'release/1.2')
    }

    // FR6: an absent --base becomes the literal "HEAD" rather than a null — the port requires a
    // non-blank baseRef, and "HEAD" is the same "null means HEAD" convention one layer down.
    def "defaults an absent base ref to the literal HEAD"() {
        given:
        def taskRepository = Mock(TaskRepository)

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, null)

        then:
        1 * taskRepository.createTask(CONTEXT, 'HEAD')
    }

    // FR7: on a fresh run both causes createTask can fail for (a branch already exists for this
    // taskId, an unresolved --base) name an operator mistake, not a resumable condition — so the
    // git-layer fault is remapped to a usage error naming the task, the cause, and the way out.
    def "remaps a creation failure into a usage error naming the task and the way out"() {
        given:
        def taskRepository = Stub(TaskRepository) {
            createTask(_, _) >> {
                throw new GitTaskRepositoryException('PROJ-1', TaskLifecycleEvent.STARTED, 'branch exists', 'gnomish/PROJ-1')
            }
        }

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, null)

        then:
        def ex = thrown(UsageException)
        ex.message.startsWith('could not start git-mode task "PROJ-1"')
        ex.message.contains('branch exists')
        ex.message.contains('--resume')
    }

    // FR7: only the git-layer failure is remapped. Any other fault propagates unchanged, so a real
    // bug is not disguised as an operator mistake with a misleading exit code.
    def "lets a non-git failure propagate unchanged"() {
        given:
        def boom = new IllegalStateException('boom')
        def taskRepository = Stub(TaskRepository) {
            createTask(_, _) >> { throw boom }
        }

        when:
        GitFreshTaskSupport.createTask(taskRepository, 'PROJ-1', CONTEXT, null)

        then:
        def thrownEx = thrown(IllegalStateException)
        thrownEx.is(boom)
    }
}
