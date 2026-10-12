package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * `tracker-take` "Exit codes by take result", against the real packaged {@code gnomish} process:
 * the codes a {@code take} ends with before any claim, and their agreement with {@code run}. Each
 * feature stands up its own project — a clone with a {@code tracker:} section and a bare origin,
 * since {@code take} binds its pipeline from origin's default branch at startup.
 *
 * <p>FR9, FR15 of add-tracker-port.
 */
class TakeExitCodeE2eSpec extends AbstractE2eProcessSpec implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    /** A project whose one stage is valid, or names an instructions file that does not exist. */
    private Path project(String name, boolean broken, String apiUrl = 'https://api.github.com') {
        def dir = initWorkingRepo(tempDir, name)
        def stage = Files.createDirectories(dir.resolve('.gnomish/stages/build'))
        Files.writeString(dir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        if (!broken) {
            Files.writeString(stage.resolve('instructions.md'), 'build it\n')
        }
        Files.writeString(stage.resolve('stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(dir.resolve('.gnomish/config.yaml'), """\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: ${apiUrl}
    repo: acme/widgets
""")
        commitAll(dir)
        addOrigin(dir, tempDir, "${name}-origin.git")
        dir
    }

    // FR15: a tracker that cannot be reached at startup — label provisioning is the first call that
    // needs it — fails the invocation before any claim, with the plain failure code.
    // Scenario: tracker/tracker-take / Failure before a claim exits 1
    def "a take whose tracker is unreachable at startup exits 1 before claiming anything"() {
        given: 'a tracker API on a port nothing listens on, and a credential so the call is really attempted'
        def dir = project('unreachable', false, 'http://127.0.0.1:9')

        when:
        def result = harness.execute('take', dir, ['--dir=' + dir], [], false, [GNOMISH_GITHUB_TOKEN: 'not-a-real-token'])

        then:
        result.exitCode() == 1

        and: 'the failure was the startup label provisioning reaching for the tracker'
        (result.stdout() + result.stderr()).contains('127.0.0.1:9/repos/acme/widgets/labels')

        and: 'no task branch was started'
        gitOutput(dir, 'branch', '--list', 'gnomish/*').isBlank()
    }

    // FR9: one exit-code table for the families the two commands share.
    // Scenario: tracker/tracker-take / Shared codes mean the same in run
    def "run and take exit with the same code for a #family"() {
        given:
        def dir = project(family.replace(' ', '-'), broken)

        when:
        def run = harness.execute('run', dir, ['--dir=' + dir] + runArgs, [])
        def take = harness.execute('take', dir, ['--dir=' + dir] + takeArgs, [])

        then:
        run.exitCode() == code
        take.exitCode() == code

        where:
        family | broken | runArgs | takeArgs || code
        'usage error' | false | ['--task=x', '--bogus=1'] | ['--bogus=1'] || 2
        'pipeline load failure' | true | ['--task=x', '--mode=in-place'] | [] || 3
    }
}
