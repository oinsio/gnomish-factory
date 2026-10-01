package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The packaged jar stops on its operator configuration before anything else happens (FR7, design
 * D6 of add-project-registry): the configuration loader runs while Spring prepares the environment,
 * before any bean exists, so the report, the exit code and the absence of a stack trace are
 * properties of the real process — what no in-process spec can show. Each feature runs against a
 * factory home of its own, set through {@code GNOMISH_HOME}.
 *
 * <p>Implements FR3, FR7, NFR-R1, UX1, UX2 of add-project-registry.
 */
class ConfigurationViolationE2eSpec extends AbstractE2eProcessSpec {

    @TempDir
    Path tmp

    // FR7, NFR-R1: "Several violations reported together" — through the real process
    def "FR7: a violating configuration exits 2, prints every violation once, and creates no worktree"() {
        given: 'a home whose host file sets a boundary key and misspells another, and a FACTORY_* variable'
        def home = FactoryHome.at(tmp.resolve('home'))
        Files.createDirectories(home.root())
        Files.writeString(home.hostConfig(), 'factory:\n  sandbox:\n    image: img:1\n  git-netwrok-timeout: 5m\n')
        Path clone = E2eFixture.projectRoot()
        ProjectRegistry.scan(home).add(new ProjectName('widgets'), clone)

        when:
        def result = harness.run(clone, [
            "--dir=$clone".toString(),
            '--task=x'
        ], [], false,
        [(FactoryHome.HOME_VARIABLE): home.root().toString(), FACTORY_SERVE_SLOTS: '4'])

        then: 'the usage-error exit code'
        result.exitCode() == 2

        and: 'one report on stderr: its heading once, then each violation once'
        def stderr = result.stderr()
        stderr.count('gnomish did not start: 3 configuration problems') == 1
        stderr.count('factory.sandbox.image is not allowed here') == 1
        stderr.count('factory.git-netwrok-timeout is not a known key') == 1
        stderr.count('environment variable FACTORY_SERVE_SLOTS is not allowed') == 1

        and: 'no stack trace, and no framework failure record'
        !(result.stdout() + stderr).contains('Exception')
        !result.stdout().contains('Application run failed')

        and: 'nothing was created: no worktree folder under the home'
        !Files.exists(home.project(new ProjectName('widgets')).dir().resolve('worktrees'))
    }

    // UX2: "Unregistered clone is refused with the fix"
    def "UX2: an unregistered --dir exits 2 with the project add line"() {
        given: 'a home with no project registered'
        def home = Files.createDirectories(tmp.resolve('empty-home'))
        Path clone = E2eFixture.projectRoot()

        when:
        def result = harness.run(clone, [
            "--dir=$clone".toString(),
            '--task=x'
        ], [], false,
        [(FactoryHome.HOME_VARIABLE): home.toString()])

        then:
        result.exitCode() == 2
        result.stderr().count("gnomish project add e2e --dir=$clone") == 1
        !(result.stdout() + result.stderr()).contains('Exception')
    }
}
