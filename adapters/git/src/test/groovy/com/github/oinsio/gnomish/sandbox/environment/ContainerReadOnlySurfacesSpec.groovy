package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.InstantSource
import spock.lang.IgnoreIf
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of add-sandbox-core: injection-persistence surfaces baked into the image
 * — agent-CLI config, shell rc files, proxy/CA/build configs — are root-owned,
 * so the gnome (the image's non-root user, which the adapter never overrides to
 * root) cannot rewrite the rules of its own cage from inside the box, while its
 * own working copy stays writable.
 *
 * <p>Docker-gated (skips cleanly with no daemon). The image is the sandbox base
 * image — its non-root {@code gnome} user owning {@code /gnomish/**} and its
 * root-owned {@code /etc/gnomish} — plus one file the base does not carry, a
 * root-owned {@code /etc/gnomish/agent.toml} as the control surface with known
 * content (design D7 of add-sandbox-base-image).
 *
 * <p>The gnome's home is a surface too (design D14 of add-sandbox-base-image): the base keeps it
 * root-owned, its shell startup files root-owned stubs, so the gnome can neither rewrite nor
 * unlink-and-recreate {@code ~/.bashrc} nor add a startup or configuration file a later process of
 * the long-lived box would read. The agent-CLI configuration under {@code ~/.claude} is not yet such
 * a surface (NG7 of add-sandbox-base-image); that remainder stands here as a pending feature.
 *
 * <p>Implements FR20, NFR-S2 of add-sandbox-core; FR12, NFR-S2 of add-sandbox-base-image.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerReadOnlySurfacesSpec extends Specification implements BareGitRepoFixture {

    static final String IMAGE = 'gnomish-sandbox-readonly-test:latest'
    static final String CONTROL_SURFACE = '/etc/gnomish/agent.toml'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final InstantSource clock = new VirtualClock()
    private ContainerTaskExecutionEnvironment env

    def setupSpec() {
        DockerImageBuilder.build(IMAGE, """
            FROM \${BASE}
            USER root
            RUN printf 'baked-rules\\n' > ${CONTROL_SURFACE} \\
             && chmod 0644 ${CONTROL_SURFACE}
            USER gnome
        """)
    }

    def setup() {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/readonly')
        env = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(new DockerCli(), IMAGE, LIMITS, clock),
                'ro-' + System.nanoTime(), new BoxGitLink(source, new ContainerHarvestFetch(new GitProcessRunner(), source)), ChildEnvAllowlist.none(), 'proj-1')
        env.materialize('task/readonly', null)
    }

    def cleanup() {
        env?.dispose()
    }

    // No cleanupSpec image removal: the test task and PIT's coverage phase run concurrently
    // (configuration-cache parallel execution), and an rmi from one JVM races the other's
    // setupSpec-build → setup-materialize window. The tagged test image is tiny and rebuilds
    // from cache instantly, so leaving it local is the race-free choice (same as GitSandboxImage).

    private int run(String script) {
        def handle = env.exec(new ExecCommand(['sh', '-c', script], [:], null, StderrPolicy.MERGED))
        handle.output().stream().readAllBytes()
        handle.waitForExit()
    }

    // Scenario: sandbox/execution-environment / Cage rules cannot be rewritten from inside
    def "FR20: the gnome can write its own working copy but not a root-owned control surface"() {
        expect: 'the gnome owns and can write the working copy'
        run('echo mine > /gnomish/work/mine.txt') == 0

        when: 'the gnome tries to rewrite the baked control surface'
        def writeExit = run("echo pwned > ${CONTROL_SURFACE}")

        then: 'the write fails and the surface is unchanged for later processes'
        writeExit != 0
        surfaceContent() == 'baked-rules'
    }

    // FR12, NFR-S2 of add-sandbox-base-image (design D14): the rc-file half of the cage rule.
    // Scenario: sandbox/execution-environment / Cage rules cannot be rewritten from inside
    // Scenario: sandbox/sandbox-base-image / The home holds no gnome-writable startup or configuration file
    def "FR20: the gnome cannot overwrite, replace or add a startup or configuration file in its home"() {
        given: "the base's stub, as a later process reads it"
        def stub = output('cat ~/.bashrc')

        expect: 'every attempt fails'
        run('echo "echo hijacked" > ~/.bashrc') != 0
        run('rm -f ~/.bashrc && echo "echo hijacked" > ~/.bashrc') != 0
        run('echo "[core]" > ~/.gitconfig') != 0
        run('echo "MAVEN_OPTS=-Dhijacked" > ~/.mavenrc') != 0

        and: 'a later process reads the stub unchanged and finds neither file'
        output('cat ~/.bashrc') == stub
        stub.startsWith('# ')
        output('ls -A ~ | grep -x -e .gitconfig -e .mavenrc; true') == ''
    }

    // NFR-S2 of add-sandbox-core: the agent CLI's own configuration is the remainder D14 leaves.
    // Scenario: sandbox/execution-environment / Cage rules cannot be rewritten from inside
    @PendingFeature(reason = 'NG7 of add-sandbox-base-image: the agent-CLI configuration under ~/.claude stays gnome-writable until D4 of harden-verification-integrity')
    def "FR20: a write to ~/.claude/settings.json fails"() {
        expect:
        run('echo "{}" > ~/.claude/settings.json') != 0
    }

    private String surfaceContent() {
        output('cat ' + CONTROL_SURFACE)
    }

    private String output(String script) {
        def handle = env.exec(new ExecCommand([
            'sh',
            '-c',
            script
        ], [:], null, StderrPolicy.PIPED))
        def out = new String(handle.output().stream().readAllBytes(), StandardCharsets.UTF_8)
        handle.waitForExit()
        out.trim()
    }
}
