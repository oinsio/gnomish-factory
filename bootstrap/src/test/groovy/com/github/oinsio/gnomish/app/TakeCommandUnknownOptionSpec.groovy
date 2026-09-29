package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1, FR8 of fix-operator-blockers (spec scenario "Rejected before side effects"): a {@code
 * gnomish take} invocation carrying an option take does not accept fails with the usage error
 * before anything durable happens — the resolved tracker sees no call at all, neither the clone nor
 * its origin gains a ref, and no worktree is created. The project is otherwise fully takeable (a
 * real clone with an origin and a {@code tracker:} section bound to a registered adapter), so the
 * only thing stopping the run is the argument check.
 */
class TakeCommandUnknownOptionSpec extends Specification
implements BareGitRepoFixture, TakeCommandFixture, ApplicationArgumentsFixture {

    @TempDir
    Path tempDir

    Path projectDir
    Path origin
    Path worktreesRoot
    Tracker tracker = Mock()

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        origin = addOrigin(projectDir, tempDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private List<String> refsOf(Path repo) {
        gitOutput(repo, 'for-each-ref', '--format=%(refname) %(objectname)').readLines()
    }

    def "NFR-R1: take with an unknown option refuses before any tracker call, branch or worktree (#form)"() {
        given:
        def command = newTakeCommand(testProperties(), worktreesRoot, [github: fakeFactory(tracker)])
        def cloneRefsBefore = refsOf(projectDir)
        def originRefsBefore = refsOf(origin)

        when:
        String[] raw = ['take'] + refs + [
            "--dir=$projectDir".toString(),
            '--no-such-option=1'
        ]
        command.run(args(raw))

        then:
        def e = thrown(UsageException)
        e.message.contains("unknown option --no-such-option for 'gnomish take'")
        0 * tracker._
        refsOf(projectDir) == cloneRefsBefore
        refsOf(origin) == originRefsBefore
        !Files.exists(worktreesRoot)

        where:
        form | refs
        'bare' | []
        'explicit' | ['github:acme/widgets#42']
    }
}
