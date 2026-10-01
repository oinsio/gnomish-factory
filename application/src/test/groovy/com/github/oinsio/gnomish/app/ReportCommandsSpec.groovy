package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import java.nio.file.Path
import java.time.Clock
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4 of collapse-composition-roots: {@link ReportCommands} routes {@code status}, {@code usage},
 * {@code board} and {@code dashboard} to their own command and leaves every other subcommand
 * untouched. The four commands are {@code final}, so each route is proven by the routed command's
 * own observable effect: {@code status} renders its listing, {@code usage} refuses an unknown task,
 * and {@code board} / {@code dashboard} each fail on a pipeline source that names the command
 * whose load it served.
 */
class ReportCommandsSpec extends Specification implements SeededCloneFixture {

    @TempDir
    Path tempDir

    ScriptedConsoleIO console = new ScriptedConsoleIO()

    def setup() {
        setupSeededClone()
    }

    private ReportCommands reports() {
        def properties = new FactoryProperties('reports-instance', null, null, null)
        def scope = RegisteredCloneFixture.scope(registeredClone)
        new ReportCommands(
                new StatusCommand(TaskGitFixture.realClaimless(), scope, console),
                new UsageCommand(TaskGitFixture.realClaimless(), scope, console),
                new BoardCommand(Clock.systemUTC(), properties, scope, wiringFailingOn('board'), console),
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), scope, properties,
                wiringFailingOn('dashboard')))
    }

    private TrackerWiring wiringFailingOn(String command) {
        def source = Stub(PipelineSource) {
            load(_) >> { throw new IOException("pipeline load for ${command}") }
        }
        new TrackerWiring([:], MapSecretsProvider.NONE, source)
    }

    def "status is routed to StatusCommand and reported as handled"() {
        when:
        def handled = reports().run(Subcommand.STATUS, new DefaultApplicationArguments('status', "--dir=${cloneDir}".toString()))

        then:
        handled
        !console.printed.isEmpty()
    }

    def "usage is routed to UsageCommand"() {
        when:
        reports().run(Subcommand.USAGE, new DefaultApplicationArguments('usage', "--dir=${cloneDir}".toString(), 'no-such-task'))

        then:
        thrown(TaskNotFoundException)
    }

    def "#name is routed to its own command"() {
        when:
        reports().run(subcommand, new DefaultApplicationArguments(name, "--dir=${cloneDir}".toString()))

        then:
        def e = thrown(IOException)
        e.message == "pipeline load for ${name}".toString()

        where:
        subcommand | name
        Subcommand.BOARD | 'board'
        Subcommand.DASHBOARD | 'dashboard'
    }

    def "#subcommand is not a report and is left unhandled"() {
        when:
        def handled = reports().run(subcommand, new DefaultApplicationArguments())

        then:
        !handled
        console.printed.isEmpty()

        where:
        subcommand << [
            Subcommand.RUN,
            Subcommand.TAKE,
            Subcommand.SERVE
        ]
    }
}
