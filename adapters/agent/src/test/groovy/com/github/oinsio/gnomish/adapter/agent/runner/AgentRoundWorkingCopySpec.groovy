package com.github.oinsio.gnomish.adapter.agent.runner

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The working-copy-unchanged check of an agent round's {@code verdict} and {@code decision} roles
 * (NFR-S2 of extract-agent-round-runner), the one check a {@code program} run takes too: git's view
 * before and after the round, each a {@code git status} the fixture answers in turn, and the stderr
 * tail of a completed round kept to one DEBUG line.
 */
@Timeout(60)
class AgentRoundWorkingCopySpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // Scenario: executor/agent-round-runner / Judge that modified the copy is refused
    def "NFR-S2: a #role round that leaves a changed file is failed/infrastructure naming the path, no answer derived"() {
        given:
        fixture.views << RunnerFixture.CLEAN << RunnerFixture.CLEAN + '? notes.txt\u0000'

        when:
        def result = fixture.start(RunnerFixture.request(role),
                RunnerFixture.exited(0, 'answer {"verdict":"pass"}\ndone\n'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': a ${role.wire()} run must leave the working copy unchanged" +
                ' (policy readOnly); the run changed notes.txt'
        result.answer() == null
        fixture.gitLaunches.size() == 2

        where:
        role << [Role.VERDICT, Role.DECISION]
    }

    // NFR-S2: a view that cannot be taken before the round refuses it before the CLI starts
    def "NFR-S2: a verdict round whose working copy cannot be viewed is refused before the CLI starts"() {
        given:
        fixture.views << RunnerFixture.exited(128, 'fatal: not a git repository')

        when:
        def result = fixture.start(RunnerFixture.request(Role.VERDICT), RunnerFixture.exited(0, 'done\n'))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().startsWith("agent executor 'fake': a verdict run must leave the working copy unchanged" +
                ' (policy readOnly), and its state could not be taken before the run: git status exited 128')
        fixture.agentLaunches.isEmpty()
    }

    // NFR-S2: a transform round changes the copy by design and is not viewed at all
    def "NFR-S2: a transform round is not checked"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'))

        then:
        result.status() == Status.COMPLETED
        fixture.gitLaunches.isEmpty()
    }

    // FR1: the stderr tail of a completed round is one DEBUG line; a failed round's rides its reason instead
    def "FR1: the stderr of a #outcome round is #logged"() {
        given:
        def logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(code, stdout), { Path answer, Path err ->
            Files.writeString(err, 'a note on stderr\n')
        })

        then:
        logs.list.any {
            it.formattedMessage.contains('a note on stderr')
        } == logged

        cleanup:
        logs.detach()

        where:
        outcome | code | stdout || logged
        'completed' | 0 | 'done\n' || true
        'broken' | 2 | 'hello m s\n' || false
    }
}
