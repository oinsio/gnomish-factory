package com.github.oinsio.gnomish.e2e

import static com.github.tomakehurst.wiremock.client.WireMock.any
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.okJson
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity claim of design D1 and D2 of add-project-registry, on the real medium: the project's
 * worktrees, its log file and its serve state are keyed by one project name by construction, so a
 * packaged jar run against a registered clone under a temporary {@code GNOMISH_HOME} writes every
 * one of them under {@code projects/<name>/} — and nothing anywhere else in the home. Component
 * specs prove each path accessor; only this spec proves that the running process takes every path
 * from the one owner.
 *
 * <p>{@code run} in git mode materializes the task's worktree and writes the log; {@code serve
 * --drain} against an in-JVM tracker with an empty queue writes the snapshot and the log. Both are
 * host-bound through the project file, and {@code serve} reads its tracker token from the project's
 * secrets folder, so no {@code --factory.*} option and no exported path variable is involved (M1).
 *
 * <p>Implements FR1, FR9, FR10, FR11, NFR-R2 of add-project-registry (design, identity spec).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RegisteredProjectLayoutE2eSpec extends Specification {

    private static final String PROJECT = 'widgets'

    @TempDir
    Path tmp

    E2eProcessHarness harness = new E2eProcessHarness()
    WireMockServer tracker = new WireMockServer(options().dynamicPort())
    FactoryHome home
    Path clone

    /**
     * The project folder, spelled out rather than asked of {@code ProjectLayout}: an expectation
     * computed by the owner under test would move with it, and the spec could not go red.
     */
    Path project

    def setup() {
        tracker.start()
        home = FactoryHome.at(tmp.resolve('home'))
        clone = E2eGitTree.copyOf('e2e')
        Files.writeString(clone.resolve('.gnomish/config.yaml'), """\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: ${tracker.baseUrl()}
    repo: acme/widgets
""")
        publish(clone, tmp.resolve('origin.git'))
        ProjectRegistry.scan(home).add(new ProjectName(PROJECT), clone)
        project = tmp.resolve('home/projects').resolve(PROJECT)
        Path projectFile = project.resolve('project.yaml')
        Files.writeString(projectFile, Files.readString(projectFile) + 'factory:\n  bindings:\n    default: host\n')
    }

    def cleanup() {
        tracker.stop()
    }

    def "FR9, FR11: run in git mode puts the task's worktree and the log under the project folder"() {
        when: 'a git-mode run starts; stdin closes at the first prompt, after the worktree exists'
        def result = harness.execute('run', clone, [
            "--dir=$clone".toString(),
            '--task=identity',
            '--interactive'
        ], [], false, env())

        then: "the banner names a worktree in the clone's own folder under the project"
        def worktree = Path.of((result.stdout() =~ /git mode: worktree (\S+)/).with {
            assert it.find(): result.stdout() + result.stderr()
            it.group(1)
        })
        worktree.parent == project.resolve('worktrees').resolve(clone.fileName.toString())
        Files.isDirectory(worktree)

        and: 'the log is the project instance log'
        Files.size(project.resolve('logs/default.log')) > 0

        and: 'the home holds nothing outside the project folder'
        outsideProject() == []
    }

    def "FR10, FR11: serve --drain puts the snapshot and the log under the project folder"() {
        given: 'a tracker whose queue is empty, and its token in the project secrets folder'
        tracker.stubFor(any(urlPathMatching('/search/.*')).willReturn(okJson('{"total_count":0,"incomplete_results":false,"items":[]}')).atPriority(1))
        tracker.stubFor(any(anyUrl()).willReturn(okJson('[]')).atPriority(10))
        secret('GNOMISH_GITHUB_TOKEN', 'token-for-the-stub')

        when:
        def result = harness.execute('serve', clone, [
            "--dir=$clone".toString(),
            '--drain'
        ], [], false, env())

        then:
        result.exitCode() == 0
        Files.isRegularFile(project.resolve('serve/default/snapshot.json'))
        Files.size(project.resolve('logs/default.log')) > 0
        outsideProject() == []
    }

    private Map<String, String> env() {
        [(FactoryHome.HOME_VARIABLE): home.root().toString()]
    }

    private void secret(String name, String value) {
        Path file = Files.createDirectories(project.resolve('secrets')).resolve(name)
        Files.writeString(file, value)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-------'))
    }

    /** Every file under the home outside the project folder — the host writes none for a project-scoped command. */
    private List<Path> outsideProject() {
        Files.walk(home.root()).withCloseable { paths ->
            paths.filter {
                Files.isRegularFile(it) && !it.startsWith(project)
            }.toList()
        }
    }

    /** Commits the tree on {@code main} and pushes it to a bare {@code origin}. */
    private static void publish(Path clone, Path origin) {
        git(origin.parent, 'init', '--quiet', '--bare', '--initial-branch=main', origin.toString())
        git(clone, 'checkout', '--quiet', '-b', 'main')
        git(clone, 'add', '--all')
        git(clone, 'commit', '--quiet', '-m', 'fixture')
        git(clone, 'remote', 'add', 'origin', origin.toString())
        git(clone, 'push', '--quiet', 'origin', 'refs/heads/main:refs/heads/main')
        git(clone, 'fetch', '--quiet', 'origin', 'refs/heads/main:refs/remotes/origin/main')
    }

    private static void git(Path dir, String... args) {
        Process process = new ProcessBuilder(['git', '-C', dir.toString()] + args.toList()).redirectErrorStream(true).start()
        String output = process.inputStream.text
        assert process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0: "git ${args.join(' ')}: ${output}"
    }
}
