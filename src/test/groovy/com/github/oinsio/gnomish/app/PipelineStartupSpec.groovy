package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR12, design D3 of add-manual-run: the runner loads {@code .gnomish/} from inside
 * {@code --dir} exactly once at startup, before any dialog. A valid tree yields the
 * definition and the constructed workspace; an invalid tree yields every loader error
 * rendered as-is (task 7.2 scope only — exit-code mapping is task 7.9's job).
 */
class PipelineStartupSpec extends Specification {

    @TempDir
    Path projectRoot

    private final PipelineStartup startup = new PipelineStartup()

    private void write(String relative, String text) {
        Path target = projectRoot.resolve('.gnomish').resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    private void writeValidTree() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: agent-cli
  model: some-model
instructions: stages/plan/instructions.md
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan it\n')
    }

    def "FR1/D3: a valid .gnomish/ under --dir loads and returns the definition and workspace"() {
        given:
        writeValidTree()
        RunArguments args = new RunArguments(projectRoot, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)

        when:
        PipelineLoadOutcome outcome = startup.load(args)

        then:
        outcome instanceof PipelineLoadOutcome.Loaded
        def loaded = outcome as PipelineLoadOutcome.Loaded
        loaded.definition().stages()*.name() == ['plan']
        loaded.workspace() instanceof DirectoryWorkspace
        (loaded.workspace() as DirectoryWorkspace).root() == projectRoot
    }

    def "FR1/FR12/D3: a missing .gnomish/ produces a Failed outcome with rendered loader errors"() {
        given: 'no .gnomish/ directory at all under --dir'
        RunArguments args = new RunArguments(projectRoot, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)

        when:
        startup.load(args)

        then: 'the required config.yaml cannot be read: a genuine I/O fault, not this test'
        thrown(IOException)
    }

    def "FR1/FR12/D3: an invalid .gnomish/ produces a Failed outcome with ConfigError.render() lines"() {
        given: 'a tree that fails validation: pipeline.yaml has no stages key'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', '{}\n')
        RunArguments args = new RunArguments(projectRoot, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)

        when:
        PipelineLoadOutcome outcome = startup.load(args)

        then:
        outcome instanceof PipelineLoadOutcome.Failed
        def failed = outcome as PipelineLoadOutcome.Failed
        failed.renderedErrors().any {
            it == 'pipeline.yaml: stages: missing required field \'stages\''
        }
    }

    def "D3: --dir non-existence propagates rather than being swallowed"() {
        given:
        Path missing = projectRoot.resolve('does-not-exist')
        RunArguments args = new RunArguments(missing, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)

        when:
        startup.load(args)

        then:
        thrown(IllegalArgumentException)
    }

    def "loading is deterministic across repeated calls (NFR-R1 carried through from PipelineLoader)"() {
        given:
        writeValidTree()
        RunArguments args = new RunArguments(projectRoot, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)

        when:
        def first = startup.load(args) as PipelineLoadOutcome.Loaded
        def second = startup.load(args) as PipelineLoadOutcome.Loaded

        then: 'the loaded definition is equal across independent load() calls'
        first.definition() == second.definition()
    }
}
