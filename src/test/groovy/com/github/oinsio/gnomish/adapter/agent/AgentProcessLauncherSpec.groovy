package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR12, D7 of add-agent-executor: the launcher spawns the configured CLI
 * binary in the workspace, with the hard-wired print-mode transport flags
 * ({@code -p}, {@code --output-format stream-json --verbose}) and the
 * environment the CLI process needs (Ollama seam, D7).
 */
class AgentProcessLauncherSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def launcher = new AgentProcessLauncher(clock)

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(workspaceDir)
    }

    // FR1, FR12: argv is binary + -p <prompt> + --output-format stream-json --verbose.
    def "command carries the binary, prompt flag, and hard-wired transport flags in order"() {
        given: 'FactoryProperties pointed at the fake agent binary as a single-token launcher'
        def properties = fakeAgentProperties('plain-round')

        when:
        def launched = launcher.launch(workspace(), 'do the thing', properties)

        then:
        launched != null
        launched.command() == [
            properties.agentCliBinary(),
            '-p',
            'do the thing',
            '--output-format',
            'stream-json',
            '--verbose'
        ]

        cleanup:
        launched?.process()?.waitFor()
    }

    // FR6, D3: startedAt is stamped from the injected clock's reading at launch time,
    // not derived from the process itself.
    def "startedAt is stamped from the injected clock"() {
        given:
        def properties = fakeAgentProperties('plain-round')
        def start = Instant.parse('2026-01-01T00:00:00Z')
        clock.instant = start

        when:
        def launched = launcher.launch(workspace(), 'goal', properties)

        then:
        launched.startedAt() == start

        cleanup:
        launched?.process()?.waitFor()
    }

    // FR1: the launched process is real and running (or already finished) — proves the
    // command list is not just built but actually handed to a real ProcessBuilder.
    def "the launched process is a real started process"() {
        given:
        def properties = fakeAgentProperties('plain-round')

        when:
        def launched = launcher.launch(workspace(), 'goal', properties)
        def exitCode = launched.process().waitFor()

        then:
        exitCode == 0
    }

    // FR1: cwd is the workspace root, mirroring CommandProcessRunner's contract.
    def "cwd is the workspace root"() {
        given:
        def properties = fakeAgentProperties('plain-round')

        when:
        def launched = launcher.launch(workspace(), 'goal', properties)
        launched.process().waitFor()

        then: 'the fake copies its scenario workspace-files/ into the cwd it was launched in'
        workspaceDir.resolve('output.txt').toFile().exists()
    }

    // NFR-R1: process-start failure (nonexistent binary) degrades to null, not a thrown
    // exception, mirroring CommandProcessRunner's precedent so the caller can turn this
    // into an infrastructure failure without a stack trace.
    def "start failure returns null instead of throwing"() {
        given: 'a binary path that cannot possibly exist'
        def properties = new FactoryProperties('factory-01', '/no/such/binary-xyz', [])

        when:
        def launched = launcher.launch(workspace(), 'goal', properties)

        then:
        launched == null
        noExceptionThrown()
    }

    // D7/FR11: ProcessBuilder inherits the full parent environment by default (this
    // codebase's existing CommandProcessRunner precedent) — the Ollama-seam env vars
    // named in agentCliEnvPassthrough reach the child process without any explicit
    // copy, since inheritance already includes them. This spec pins that behaviour so a
    // future change cannot silently start stripping the environment: the fake agent
    // script itself depends on PATH (its shebang line, and the `sh` it execs) working,
    // so a successful run is proof the environment reached the child process.
    def "environment reaches the child process, including passthrough-listed vars"() {
        given:
        def properties = fakeAgentProperties('plain-round', ['PATH'])

        when:
        def launched = launcher.launch(workspace(), 'goal', properties)
        def exitCode = launched.process().waitFor()

        then:
        exitCode == 0
    }

    // FR11, FR12, D7: the model+settings overload inserts --model and settings flags
    // after the prompt, before the transport flags.
    def "model and settings overload inserts invocation options after the prompt"() {
        given:
        def properties = fakeAgentProperties('plain-round')

        when:
        def launched = launcher.launch(
                workspace(), 'do the thing', properties, 'claude-sonnet-4-5', [maxTurns: 3])

        then:
        launched != null
        launched.command() == [
            properties.agentCliBinary(),
            '-p',
            'do the thing',
            '--model',
            'claude-sonnet-4-5',
            '--max-turns',
            '3',
            '--output-format',
            'stream-json',
            '--verbose'
        ]

        cleanup:
        launched?.process()?.waitFor()
    }

    // FR11: an empty settings map still renders --model, nothing else.
    def "model and settings overload with empty settings renders only --model"() {
        given:
        def properties = fakeAgentProperties('plain-round')

        when:
        def launched = launcher.launch(workspace(), 'goal', properties, 'm', [:])

        then:
        launched.command() == [
            properties.agentCliBinary(),
            '-p',
            'goal',
            '--model',
            'm',
            '--output-format',
            'stream-json',
            '--verbose'
        ]

        cleanup:
        launched?.process()?.waitFor()
    }

    // FR3, NFR-S2, D1: the model+settings overload accepts an extra-env fragment (the
    // decision-file transport's GNOMISH_DECISION_FILE) so task 6.5 can wire it into the
    // child process without this launcher knowing anything about the decision protocol.
    // The decision-needed fake scenario writes to $GNOMISH_DECISION_FILE when it is set,
    // so a written file is proof the extra-env fragment actually reached the child.
    def "extra env overload merges additional variables into the child process environment"() {
        given:
        def properties = fakeAgentProperties('decision-needed')
        def decisionFile = workspaceDir.resolve('decision.json')

        when:
        def launched = launcher.launch(
                workspace(), 'goal', properties, 'm', [:], ['GNOMISH_DECISION_FILE': decisionFile.toString()])
        def exitCode = launched.process().waitFor()

        then: 'the command shape is unchanged from the model+settings overload'
        exitCode == 0
        launched.command() == [
            properties.agentCliBinary(),
            '-p',
            'goal',
            '--model',
            'm',
            '--output-format',
            'stream-json',
            '--verbose'
        ]

        and: 'the extra env var reached the child process'
        decisionFile.toFile().exists()
    }

    private static FactoryProperties fakeAgentProperties(String scenario, List<String> envPassthrough = []) {
        // The fake agent script cannot be invoked as a single argv[0] binary (Gradle's
        // resource copy / a fresh checkout does not reliably preserve the executable
        // bit — see fake-agent/README.md), so this spec wraps it in a tiny shell script
        // that exports the scenario the fake reads from GNOMISH_FAKE_SCENARIO and execs
        // `sh <fake-agent.sh> "$@"` — giving AgentProcessLauncher a single binary path to
        // invoke directly, exactly like the real `claude` binary.
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = "#!/bin/sh\nexport GNOMISH_FAKE_SCENARIO='${scenario}'\nexec sh '${FakeAgentBinary.commandPrefix()[1]}' \"\$@\"\n"
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('factory-01', wrapper.absolutePath, envPassthrough)
    }
}
