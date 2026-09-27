package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared "minimal one-stage {@code .gnomish/} pipeline on a real project root" setup used by
 * every {@code gnomish run} spec that drives the flow end to end against a real {@code --dir}:
 * one {@code build} stage with a no-op {@code files_exist} check, and the git-clone seeding
 * {@code TaskBranchCreator} needs to cut a task branch off {@code HEAD} (FR7 of
 * add-git-workflow). Extracted from {@code ManualRunRunnerSpec} and {@code
 * ManualRunContainerDispatchSpec}, which had each hand-duplicated the same three methods.
 */
trait ManualRunPipelineFixture implements BareGitRepoFixture {

    void write(Path projectRoot, String relative, String text) {
        Path target = projectRoot.resolve('.gnomish').resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    void writeOneStagePipeline(Path projectRoot) {
        write(projectRoot, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write(projectRoot, 'pipeline.yaml', 'stages:\n  - build\n')
        write(projectRoot, 'stages/build/stage.yaml', '''\
purpose: build the thing
executor:
  type: agent-cli
  model: some-model
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: []
advancement: auto
''')
        write(projectRoot, 'stages/build/instructions.md', 'build it\n')
    }

    /**
     * Turns {@code projectRoot} into a real, non-bare git clone with one commit — the {@code
     * --dir} git mode needs (FR7 of add-git-workflow): {@code TaskBranchCreator} branches off
     * {@code HEAD}, which requires at least one commit to resolve.
     */
    void makeProjectRootAGitClone(Path projectRoot) {
        assert gitExitCode(projectRoot, 'init') == 0
        Files.writeString(projectRoot.resolve('README.md'), 'seed\n')
        commitAll(projectRoot)
    }
}
