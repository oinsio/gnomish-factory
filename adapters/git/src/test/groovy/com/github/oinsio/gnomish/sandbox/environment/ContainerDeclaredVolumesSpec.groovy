package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.InstantSource
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, M1, G1 of fix-image-declared-volumes: the identity claim of the design's
 * single-owner table — "the set of paths overridden equals the set of paths the image
 * declares minus the explicit mounts" — asserted end to end on the real daemon, which is
 * the only medium where an anonymous volume can actually come into existence. The unit
 * specs prove each link (the resolver reads, the builders render); only this one proves
 * the links are joined and that no anonymous volume comes into existence across a full
 * materialize → round → dispose.
 *
 * <p>Attributed to this spec's own containers, never the daemon-wide volume list, which
 * concurrent suites change: an anonymous volume shows in its container's {@code volume}
 * mounts by a hex name, so the box may mount only the factory volume and the guard none.
 *
 * <p>Two media, both of which leaked before the change: a task image declaring a
 * {@code VOLUME} ({@link DeclaredVolumeSandboxImage}, two paths — one outside the factory's
 * mounts, one equal to the working copy) and the default guard image, whose mitmproxy
 * confdir declaration is the leak the change was opened for (one anonymous volume per
 * guard start, reproduced 2026-09-12).
 *
 * <p>Docker-gated (skips cleanly with no daemon); the guard feature additionally needs the
 * real guard image to be present or pullable.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerDeclaredVolumesSpec extends Specification implements BareGitRepoFixture {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final InstantSource clock = new VirtualClock()
    private final DockerCli docker = new DockerCli()

    private String key
    private ContainerTaskExecutionEnvironment env

    def setup() {
        key = 'dv-' + System.nanoTime()
    }

    def cleanup() {
        env?.dispose()
    }

    def "FR5: an image-declared path is an ephemeral override and leaves no anonymous volume"() {
        when: 'an environment is materialized from an image declaring /cache and the working copy'
        materialize(DeclaredVolumeSandboxImage.ensureBuilt())

        then: 'the declared path outside the factory mounts is a memory-backed filesystem in the box'
        mountLine(DeclaredVolumeSandboxImage.DECLARED_PATH).contains('tmpfs')

        and: 'the explicitly mounted working copy keeps its named factory volume — not an override'
        !mountLine(ContainerTaskExecutionEnvironment.WORKING_COPY).contains('tmpfs')
        containerMounts().contains('volume:' + FactoryDockerLabels.volumeName(key) + ':'
                + ContainerTaskExecutionEnvironment.WORKING_COPY)

        and: 'the box mounts no volume but the factory one — no anonymous volume was created with it'
        def boxVolumes = volumeMounts(FactoryDockerLabels.containerName(key))
        boxVolumes == Set.of(FactoryDockerLabels.volumeName(key))

        and: 'image content under the declared path is not visible in the box (design D1, UX2)'
        exitOf('test -e ' + DeclaredVolumeSandboxImage.BAKED_FILE) != 0

        and: 'the override is mounted root-owned and world-writable with the sticky bit (D5)'
        // The runtime copies the image directory's mode onto the tmpfs but never its owner — the
        // mount comes up root:root (runc's tmpfs path has no chown; moby/moby#39466), while the
        // anonymous volume this change prevents copied owner and mode alike. `tmpfs-mode=1777` is
        // what gives the image's own non-root user back the write access it had before.
        output("stat -c '%a %U' " + DeclaredVolumeSandboxImage.DECLARED_PATH).strip() == '1777 root'

        when: 'a round writes into the declared path as the image\'s non-root user'
        def writeExit = exitOf('echo written > ' + DeclaredVolumeSandboxImage.DECLARED_PATH + '/round.txt')

        then: 'the write succeeds — this assertion is the runc-floor gate of D5'
        // A runtime older than runc 1.1.8 (opencontainers/runc#3912) restores the image
        // directory's own mode after mounting and silently ignores tmpfs-mode; on such a host the
        // declared path comes up root-owned and not writable by `gnome`, and this line reports it.
        writeExit == 0

        when: 'the environment is disposed'
        env.dispose()
        env = null

        then: 'no volume the box had, and none of this environment, is left behind'
        leftOver(boxVolumes).isEmpty()
    }

    @IgnoreIf({
        !GuardImageAvailability.available()
    })
    def "FR5: creating and disposing a guard from the default guard image leaves no anonymous volume"() {
        given: 'a materialized environment, so the guard has its task network to join'
        materialize(GitSandboxImage.ensureBuilt())
        // GuardImageAvailability.IMAGE mirrors SandboxProperties.DEFAULT_GUARD_IMAGE, which is
        // package-private to :sandbox:core; the fixture is the published spelling of that default.
        def guard = new EgressGuard(docker, key, GuardImageAvailability.IMAGE, [], tempDir.resolve('guard-cfg'),
        new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))

        when: 'the guard is created — the mitmproxy image declares its confdir as a VOLUME'
        guard.ensureRunning()

        then: 'it is running'
        docker.run(GuardCommands.inspectGuardRunning(key)).stdout().forParsing().strip() == 'true'

        and: 'its declared confdir got no anonymous volume — the guard mounts no volume at all'
        def guardVolumes = volumeMounts(FactoryDockerLabels.guardName(key))
        guardVolumes.isEmpty()
        def boxVolumes = volumeMounts(FactoryDockerLabels.containerName(key))

        when: 'the environment (and with it the guard) is disposed'
        env.dispose()
        env = null

        then: 'the regression this change was opened for is gone: no volume was left behind'
        leftOver(guardVolumes + boxVolumes).isEmpty()
    }

    private void materialize(String image) {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/declared-volumes')
        env = new ContainerTaskExecutionEnvironment(
                docker,
                key,
                new BoxGitLink(source, new ContainerHarvestFetch(new GitProcessRunner(), source)),
                new TaskContainerSettings(image, 'runc', LIMITS, false),
                clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        env.materialize('task/declared-volumes', null)
    }

    /** The names of one container's {@code volume} mounts — an anonymous volume shows here by its hex name. */
    private Set<String> volumeMounts(String container) {
        inspect(container, '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}} {{end}}{{end}}')
                .split(/\s+/).findAll { it }.toSet()
    }

    /** Of {@code seen}, and of the volumes labelled with this environment's key, those the daemon still has. */
    private Set<String> leftOver(Set<String> seen) {
        def ownLabel = 'label=' + FactoryDockerLabels.taskLabelAssignment(key)
        volumeList([]).intersect(seen) + volumeList(['--filter', ownLabel])
    }

    private Set<String> volumeList(List<String> filter) {
        def result = docker.run(['volume', 'ls', '-q'] + filter)
        assert result.ok(): "docker volume ls failed: ${result.stderr()}"
        result.stdout().forParsing().readLines()*.strip().findAll { it }.toSet()
    }

    /** The box's own view of what is mounted at {@code path} — the tmpfs proof from inside. */
    private String mountLine(String path) {
        output("mount | grep ' ${path} '")
    }

    /** The daemon's view of the container's mounts, as {@code <type>:<name>:<destination>} entries. */
    private String containerMounts() {
        inspect(FactoryDockerLabels.containerName(key), '{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}} {{end}}')
    }

    private String inspect(String container, String format) {
        def result = docker.run(['inspect', '-f', format] << container)
        assert result.ok(): "docker inspect failed: ${result.stderr()}"
        result.stdout().forParsing()
    }

    private String output(String script) {
        def handle = env.exec(new ExecCommand(['sh', '-c', script], [:], null, true))
        def out = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8)
        handle.waitForExit()
        out
    }

    private int exitOf(String script) {
        def handle = env.exec(new ExecCommand(['sh', '-c', script], [:], null, true))
        handle.output().readAllBytes()
        handle.waitForExit()
    }
}
