package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, D3 of add-agent-executor: {@link LaunchedAgentProcess#waitForExitMeasuringWallTime}
 * measures process start → exit independent of stream-json parsing — the caller
 * never reads the process's stdout in these specs, proving the measurement does
 * not depend on it.
 */
class LaunchedAgentProcessSpec extends Specification {

    @TempDir
    Path workspaceDir

    def launchClock = new VirtualClock()

    def launcher = new AgentProcessLauncher(launchClock)

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(workspaceDir)
    }

    // FR6, D3: wall time is the difference between startedAt and the exit-time clock
    // reading, computed with no dependency on having parsed the process's stdout.
    def "wall time is the exact duration between startedAt and the exit-time clock reading"() {
        given: 'a launch clock stamping startedAt, and a real short-lived fake-agent process'
        def start = Instant.parse('2026-01-01T00:00:00Z')
        launchClock.instant = start
        def launched = launcher.launch(workspace(), 'goal', fakeAgentProperties('plain-round'))

        and: 'a separate exit clock seeded five seconds later'
        def exitClock = new VirtualClock(start.plusSeconds(5))

        when:
        def wallTime = launched.waitForExitMeasuringWallTime(exitClock)

        then:
        wallTime == Duration.ofSeconds(5)
    }

    // FR6, D3: the measurement blocks until the process has actually exited — proven
    // by the process reporting a real exit code once this method returns.
    def "the process has exited by the time waitForExitMeasuringWallTime returns"() {
        given:
        def launched = launcher.launch(workspace(), 'goal', fakeAgentProperties('plain-round'))
        def exitClock = new VirtualClock()

        when:
        launched.waitForExitMeasuringWallTime(exitClock)

        then:
        !launched.process().isAlive()
        launched.process().exitValue() == 0
    }

    // FR6, D3: measurement never touches the process's stdout — proven by driving a
    // scenario whose stdout is never read here at all, only waited on for exit.
    def "wall time is measurable without ever reading the process's stdout"() {
        given:
        def launched = launcher.launch(workspace(), 'goal', fakeAgentProperties('subagent-round'))
        def exitClock = new VirtualClock(launched.startedAt().plusSeconds(2))

        when:
        def wallTime = launched.waitForExitMeasuringWallTime(exitClock)

        then:
        wallTime == Duration.ofSeconds(2)
    }

    private static FactoryProperties fakeAgentProperties(String scenario) {
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = "#!/bin/sh\nexport GNOMISH_FAKE_SCENARIO='${scenario}'\nexec sh '${FakeAgentBinary.commandPrefix()[1]}' \"\$@\"\n"
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('factory-01', wrapper.absolutePath, [])
    }
}
