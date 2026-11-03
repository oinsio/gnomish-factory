package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gittransfer.GitInvocation
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link WorkingCopyCheck#view} against {@link FakeProcess}, handed to the host environment through
 * its {@link ProcessLaunch} (FR11, NFR-S4 of define-executor-contract; testing.md, "A scheduling order
 * is never a premise"): the command the check runs, and every way a view is not taken — a refused
 * start, a failing git, an output over the cap, a git past the bound, an interrupted wait.
 */
@Timeout(60)
class WorkingCopyViewSpec extends Specification {

    @TempDir
    Path workspace

    ProcessBuilder launched

    private WorkingCopyView view(Closure<Process> process, Duration bound = Duration.ofSeconds(30)) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            launched = builder
            process()
        } as ProcessLaunch
        def environment = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        environment.materialize('gnomish/TASK-7', null)
        try {
            new WorkingCopyCheck(environment, bound, new VirtualClock()).view()
        } finally {
            environment.dispose()
        }
    }

    // NFR-S4: git's own view, under the factory's invocation policy, in the working copy, stderr merged
    def "NFR-S4: the view is git status in porcelain v2 under the invocation policy, run in the working copy"() {
        when:
        def view = view({
            new FakeProcess(0, '# branch.oid abc\u0000? notes.txt\u0000') {}
        })

        then:
        view == new WorkingCopyView.Taken([
            '# branch.oid abc',
            '? notes.txt'
        ] as LinkedHashSet)
        launched.command() == GitInvocation.commandLine('git', [
            '-c',
            'core.fileMode=true',
            '--no-optional-locks',
            'status',
            '--porcelain=v2',
            '-z',
            '--branch',
            '--untracked-files=all',
            '--ignored=no',
            '--ignore-submodules=none'
        ])
        launched.directory().toPath() == workspace
        launched.redirectErrorStream()
    }

    // NFR-S4: a git that fails gives no view; its words are quoted through the log exit
    def "NFR-S4: a git that exits non-zero gives no view, naming the code and what it said"() {
        when:
        def view = view({
            new FakeProcess(128, 'fatal: not a git repository\u001b[31m') {}
        })

        then:
        view instanceof WorkingCopyView.Unreadable
        view.why().contains('git status exited 128: ')
        view.why().contains('fatal: not a git repository')
        !view.why().contains('\u001b')
    }

    // NFR-S4: a view over the cap cannot be judged whole, so none is taken
    def "NFR-S4: an output over the cap gives no view"() {
        when:
        def view = view({
            new FakeProcess(0, '? ' + 'x' * WorkingCopyCheck.VIEW_CAP_BYTES) {}
        })

        then:
        view.why() == "git status wrote more than ${WorkingCopyCheck.VIEW_CAP_BYTES} bytes, or held its output open"
    }

    // NFR-S4: an output exactly at the cap is read whole
    def "NFR-S4: an output exactly at the cap is a view"() {
        when:
        def view = view({
            new FakeProcess(0, '? ' + 'x' * (WorkingCopyCheck.VIEW_CAP_BYTES - 2)) {}
        })

        then:
        view instanceof WorkingCopyView.Taken
    }

    // NFR-S4: a start the environment refuses gives no view
    def "NFR-S4: a refused start gives no view, naming the refusal"() {
        when:
        def view = view({ throw new IOException('no git here') })

        then:
        view.why().startsWith('the environment refused to start git: ')
        view.why().contains('could not start process: git')
    }

    // NFR-S4: a git past the bound is killed and gives no view
    def "NFR-S4: a git past the bound gives no view, naming the bound"() {
        given:
        def process = FakeProcess.running()

        when:
        def view = view({ process }, Duration.ofMillis(50))

        then:
        view.why() == 'git status did not finish within PT0.05S'
        !process.alive
    }

    // NFR-S4: an interrupted wait gives no view
    def "NFR-S4: an interrupted wait gives no view"() {
        given:
        def process = FakeProcess.running()

        when:
        Thread.currentThread().interrupt()
        def view = view({ process })
        boolean flagged = Thread.interrupted()

        then:
        view.why() == 'the wait for git status was interrupted'
        flagged
    }
}
