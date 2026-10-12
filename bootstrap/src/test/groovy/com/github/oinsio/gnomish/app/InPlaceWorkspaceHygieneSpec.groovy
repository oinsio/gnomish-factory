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
 * The in-place mode's promise to the workspace it runs in (FR1, NFR-S1 of add-manual-run; FR10,
 * NFR-S2 of add-agent-executor): the runner process writes nothing there. A real in-place run —
 * the fake agent's round and two command checks that write findings files — leaves the workspace
 * with exactly the gnome's own file added, every runner-created file (findings channel, decision
 * transport, log) living elsewhere.
 *
 * <p>Implements FR1, NFR-S1 of add-manual-run; NFR-S2 of add-agent-executor.
 */
class InPlaceWorkspaceHygieneSpec extends Specification implements AppAssemblyFixture, ManualRunPipelineFixture {

    @TempDir
    Path projectRoot

    @TempDir
    Path homeDir

    // Scenario: cli/manual-run / No runner artifacts in the workspace
    def "FR1, NFR-S1: an in-place run with findings-writing command checks adds only the gnome's file to the workspace"() {
        given: 'a one-stage pipeline whose two command checks each write a findings file'
        write(projectRoot, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write(projectRoot, 'pipeline.yaml', 'stages:\n  - build\n')
        write(projectRoot, 'stages/build/stage.yaml', '''\
purpose: build the thing
executor:
  type: agent-cli
  model: some-model
instructions: stages/build/instructions.md
verify:
  - type: command
    command: printf '{"findings":[{"message":"lint note"}]}' > "$GNOMISH_FINDINGS_FILE"
  - type: command
    command: printf '{"findings":[{"message":"test note"}]}' > "$GNOMISH_FINDINGS_FILE"
advancement: auto
''')
        write(projectRoot, 'stages/build/instructions.md', 'build it\n')
        def before = listing(projectRoot)
        def runner = newManualRunRunner(projectRoot, homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]), TaskGitFixture.real(), FakeAgentSupport.propertiesFor('plain-round'))

        when:
        runner.run(new DefaultApplicationArguments(
                        "--dir=${projectRoot}".toString(), '--task=do the thing', '--task-id=hygiene-in-place', '--mode=in-place'))

        then: 'the run completed through both checks'
        noExceptionThrown()

        and: 'the only path the run added is the file the gnome wrote'
        listing(projectRoot) - before == ['output.txt']
    }

    private static List<String> listing(Path root) {
        Files.walk(root).withCloseable { paths ->
            paths.filter {
                it != root
            }.map {
                root.relativize(it).toString()
            }.sorted().toList()
        }
    }
}
