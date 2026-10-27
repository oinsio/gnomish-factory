package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.doctor.DoctorCheckId
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.TempDir

/**
 * {@code gnomish doctor} through the packaged jar on a deliberately broken setup (M3 of
 * add-doctor-command): a registered clone whose one stage is container-bound, with the tracker
 * token absent and the configured sandbox image absent from the local daemon. One run reports both
 * as {@code fail} with their fixes, every other check still has its line, the exit code is 1 — and
 * the run leaves nothing behind (NFR-R1): no worktree in the clone or under the factory home, no
 * file under the home but the run's log, no image pulled, and no container created, neither a box
 * nor the one-shot helper that reads the box image's git version (NG1).
 *
 * <p>The containers are proven absent from the daemon's own event stream ({@link DockerEventWatch}),
 * not from a listing: the git floor helper is {@code docker run --rm} and would be gone before any
 * listing. A container the run created would carry the project label of this spec's unique
 * {@code factory.sandbox.project-id} (every factory create command splices the ownership labels in)
 * or this spec's unique image.
 *
 * <p>Docker-gated, like the rest of the container E2E layer: with no daemon the Docker check itself
 * fails and the run has a third {@code fail}, so the feature skips instead.
 *
 * <p>NFR-R1, NFR-P1, NG1, M3 of add-doctor-command.
 */
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
}, reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class DoctorE2eSpec extends AbstractE2eProcessSpec implements BareGitRepoFixture {

    /** The agent credential the round would get — a placeholder, asserted never to be printed. */
    private static final String AGENT_CREDENTIAL = 'placeholder-oauth-token-not-a-credential'

    @TempDir
    Path tempDir

    private final String token = UUID.randomUUID().toString()
    private final String image = "gnomish-doctor-e2e-absent:${token}"
    private final String projectId = "doctor-e2e-${token}"

    /** A clone with a GitHub tracker section and one agent stage, which binds the container adapter. */
    private Path brokenClone() {
        Path dir = initWorkingRepo(tempDir, 'widgets')
        Path stage = Files.createDirectories(dir.resolve('.gnomish/stages/build'))
        Files.writeString(dir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(stage.resolve('instructions.md'), 'build it\n')
        Files.writeString(stage.resolve('stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(dir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(dir)
        dir
    }

    /** Registers {@code clone} in {@code home} with the container binding, the absent image and the project id. */
    private void register(FactoryHome home, Path clone) {
        ProjectRegistry.scan(home).add(new ProjectName('widgets'), clone)
        Path config = home.project(new ProjectName('widgets')).config()
        String text = Files.readString(config)
        Files.writeString(config, (text.endsWith('\n') ? text : text + '\n') + """\
factory:
  bindings:
    default: container
  sandbox:
    image: ${image}
    project-id: ${projectId}
""")
    }

    private static Set<Path> tree(Path root) {
        Files.walk(root).withCloseable { paths -> paths.toList().toSet() }
    }

    // M3, NFR-R1, NG1: the broken setup in one run, through the real process, with no side effect
    // NFR-P1: the offline run, Docker running, finishes in under five seconds
    // Scenario: cli/operator-doctor / Several problems in one run
    def "a missing token and a missing sandbox image are two fail lines of one run that exits 1 and starts nothing"() {
        given: 'a factory home of its own, holding the one registered clone'
        FactoryHome home = FactoryHome.at(Files.createDirectories(tempDir.resolve('home')))
        Path dir = brokenClone()
        register(home, dir)

        and: 'the image is really absent, and the state the run must not change is recorded'
        assert !DockerEventWatch.imagePresent(image)
        String worktreesBefore = gitOutput(dir, 'worktree', 'list', '--porcelain')
        Set<Path> homeBefore = tree(home.root())

        when: 'doctor runs once, with an agent credential and without the tracker token'
        DockerEventWatch watch = DockerEventWatch.open(token)
        def result
        List<String> events
        long started = System.nanoTime()
        long elapsed
        try {
            result = harness.execute('doctor', dir, ['--dir=' + dir], [], false, [
                (FactoryHome.HOME_VARIABLE): home.root().toString(),
                CLAUDE_CODE_OAUTH_TOKEN: AGENT_CREDENTIAL
            ])
            elapsed = System.nanoTime() - started
        } finally {
            events = watch.close()
        }
        List<String> lines = result.stdout().readLines()
        List<String> failed = lines.findAll { it.startsWith('fail  ') }

        then: 'the failure exit code'
        result.exitCode() == 1

        and: 'the whole offline run, the JVM start included, took under five seconds (NFR-P1)'
        elapsed <java.util.concurrent.TimeUnit.SECONDS.toNanos(5)

        and: 'exactly two fail lines: the tracker token and the sandbox image, each with its fix'
        failed.size() == 2
        failed.count {
            it.startsWith('fail  declared secret: GNOMISH_GITHUB_TOKEN not found')
        } == 1
        failed.count {
            it.startsWith('fail  sandbox image: ') && it.contains(image)
        } == 1
        failed.every { it.contains('; fix: ') }
        failed.find {
            it.startsWith('fail  sandbox image: ')
        }.contains("docker pull ${image}")

        and: 'the sandbox image line names the box git floor as not probed'
        failed.find {
            it.startsWith('fail  sandbox image: ')
        }.contains("the box image's git floor is not probed")

        and: "docker's closing line end is not rendered as an inert \\n in the detail"
        !failed.find {
            it.startsWith('fail  sandbox image: ')
        }.contains('\\n')

        and: 'every offline check still has its line, and the summary counts two fails'
        DoctorCheckId.values().findAll { !it.online() }.every { id ->
            lines.any {
                it ==~ /(ok|warn|fail|skipped)  ${java.util.regex.Pattern.quote(id.displayName())}: .*/
            }
        }
        lines.last().startsWith('summary: ')
        lines.last().contains(' 2 fail,')

        and: 'no secret value is printed'
        !(result.stdout() + result.stderr()).contains(AGENT_CREDENTIAL)

        and: 'no worktree: the clone lists the same worktrees, and nothing but the log appeared under the home'
        gitOutput(dir, 'worktree', 'list', '--porcelain') == worktreesBefore
        (tree(home.root()) - homeBefore).every {
            Files.isDirectory(it) || it.fileName.toString().endsWith('.log')
        }
        !tree(home.root()).any { it.fileName.toString() == 'worktrees' }

        and: 'no container was created — neither a box nor the git floor helper — and no image was pulled'
        events.findAll {
            it.startsWith('container ') && (it.contains(projectId) || it.contains(image))
        } == []
        !DockerEventWatch.imagePresent(image)

        and: 'no hook or other planted program of the clone ran'
        assertNothingPlantedRan(dir)
    }
}
