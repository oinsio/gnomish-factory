package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.Answer
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 of fix-terminal-receipt-convergence (fourth amendment): the live group registry
 * holds the groups the launch owner started and no sweep has found empty — registered once {@code
 * start()} returned, removed by the sweep whose probe is refused — and the shutdown step's {@code
 * sweepLive} sweeps exactly those. Every feature builds a registry of its own, so nothing it
 * registers reaches the process's one registry or the next feature.
 */
class LiveGroupsSpec extends Specification {

    @TempDir
    Path work

    LiveGroups groups = new LiveGroups()

    def "FR21: a started child's group is registered once its start returned"() {
        when:
        Process process = launch().start(command('sleep', '600'), StdinSource.NONE)

        then:
        groups.current() == [process.pid()]

        cleanup:
        process?.destroyForcibly()
    }

    def "FR21: the waiter's sweep deregisters the group once its probe is refused"() {
        given:
        CaptureRunner runner = new CaptureRunner(
                new ProcessSupervisor(), launch(), new ProcessGroupSweep(GroupedLaunch.groupSignal(), groups))

        when:
        Captured captured = runner.run(command('true'), null)

        then:
        captured.exitCode() == 0
        groups.current().isEmpty()
    }

    def "FR21: a launch that threw registers nothing: #shape"() {
        when:
        launch().start(builder.call(work), StdinSource.NONE)

        then:
        thrown(IOException)
        groups.current().isEmpty()

        where:
        shape | builder
        'the binary does not resolve' | { Path dir ->
            command(dir.resolve('missing').toString())
        }
        'the working directory is gone' | { Path dir ->
            command('true').directory(dir.resolve('gone').toFile())
        }
    }

    def "FR21: a group that still answers after the last round, or never answered, stays registered: #answers"() {
        given:
        groups.register(4242)

        when:
        new ProcessGroupSweep(new ProcessProbes(answers, []), groups).sweep(new FakeProcess().leading(4242))

        then: 'the shutdown step will try it again'
        groups.current() == [4242L]

        where:
        answers << [
            [Answer.ANSWERED] * 6,
            [Answer.UNSENT]
        ]
    }

    def "FR21: the shutdown step's sweep probes every registered group, and forgets the ones found empty"() {
        given:
        groups.register(4242)
        groups.register(4343)
        List<String> sent = []

        when:
        new ProcessGroupSweep(new ProcessProbes([
            Answer.REFUSED,
            Answer.REFUSED
        ], sent), groups).sweepLive()

        then:
        sent as Set == ['0 4242', '0 4343'] as Set
        groups.current().isEmpty()
    }

    def "FR21: a registry is left untouched by a sweep of a group it never held"() {
        given:
        groups.register(4242)

        when:
        new ProcessGroupSweep(new ProcessProbes([Answer.REFUSED], []), groups).sweep(new FakeProcess().leading(4343))

        then:
        groups.current() == [4242L]
    }

    def "FR21: a group swept twice, by its waiter and by the shutdown step, costs the second one refused probe"() {
        given:
        Process process = launch().start(command('true'), StdinSource.NONE)
        process.waitFor(10, TimeUnit.SECONDS)
        List<String> sent = []
        ProcessGroupSweep sweep = new ProcessGroupSweep(new ProcessProbes(GroupedLaunch.groupSignal(), sent), groups)

        when:
        sweep.sweep(process)
        sweep.sweepLive()

        then: 'the waiter\'s probe was refused and removed it, so the step had nothing left to probe'
        sent == [
            "0 ${process.pid()}".toString()
        ]
        groups.current().isEmpty()
    }

    private GroupedLaunch launch() {
        String path = System.getenv('PATH')
        new GroupedLaunch(work, path, new HostLauncher(System.getProperty('os.name'), path, HostLauncher.KILL), groups)
    }

    private static ProcessBuilder command(String... argv) {
        ProcessBuilder builder = new ProcessBuilder(argv)
        TestChildEnvironment.cleared(builder)
        builder
    }
}
