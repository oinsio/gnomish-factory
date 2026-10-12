package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The definition snapshot of a run (FR1 of add-manual-run; FR19, NFR-S2 of add-sandbox-core): the
 * {@code .gnomish/} a run loaded at startup is the one it runs to the end, and an edit made while
 * it runs reaches only the next invocation. Driven in place, where the definition and the
 * workspace are one directory, by the fake agent's {@code law-tamper-then-plain} round: its first
 * attempt rewrites {@code .gnomish/instructions.md} and fails its check, its second completes; each
 * round's prompt is captured from the agent's stdin.
 *
 * <p>Implements FR1 of add-manual-run; FR19, NFR-S2 of add-sandbox-core.
 */
class InPlaceDefinitionSnapshotSpec extends Specification implements AppAssemblyFixture, ManualRunPipelineFixture {

    private static final String ORIGINAL = 'ORIGINAL LAW: write output.txt.'
    private static final String TAMPERED = 'TAMPERED LAW'

    @TempDir
    Path projectRoot

    @TempDir
    Path homeDir

    @TempDir
    Path captures

    // Scenario: cli/manual-run / A definition edit waits for the next run
    def "FR1: a .gnomish/ edit made during a run is not seen by it, and is by the next invocation"() {
        given: 'a one-stage pipeline whose instructions the first round rewrites in place'
        write(projectRoot, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write(projectRoot, 'pipeline.yaml', 'stages:\n  - build\n')
        write(projectRoot, 'stages/build/stage.yaml', '''\
purpose: build the thing
executor:
  type: agent-cli
  model: some-model
instructions: instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: [output.txt]
advancement: auto
''')
        write(projectRoot, 'instructions.md', ORIGINAL + '\n')
        def capture = captures.resolve('law-tamper-then-plain.log')
        def properties = testProperties(agentCliBinary: FakeAgentSupport.binaryCapturingStdin('law-tamper-then-plain', capture))

        when: 'the first run tampers in its first round, then completes in its second'
        runInPlace(properties, 'snapshot-1')

        then: 'both of its rounds were briefed with the definition it loaded at startup'
        def first = prompts(capture)
        first.size() == 2
        first.every { it.contains(ORIGINAL) && !it.contains(TAMPERED) }

        when: 'the next invocation starts after the edit'
        runInPlace(properties, 'snapshot-2')

        then: 'it loaded the edited definition'
        def next = prompts(capture).drop(2)
        !next.isEmpty()
        next.every { it.contains(TAMPERED) }
    }

    private void runInPlace(def properties, String taskId) {
        newManualRunRunner(projectRoot, homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]), TaskGitFixture.real(), properties)
                .run(new DefaultApplicationArguments("--dir=${projectRoot}".toString(), '--task=do the thing',
                "--task-id=${taskId}".toString(), '--mode=in-place'))
    }

    private static List<String> prompts(Path capture) {
        Files.readString(capture).split('(?m)^---$')*.trim().findAll {
            !it.isEmpty()
        }
    }
}
