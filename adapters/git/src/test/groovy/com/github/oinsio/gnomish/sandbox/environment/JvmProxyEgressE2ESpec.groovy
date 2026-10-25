package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.InstantSource
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The behavioral proof that the tools in a box ride the sandbox base image's inherited proxy
 * plumbing through the guard (FR7, UX4 of add-sandbox-core; FR10 of add-sandbox-base-image): a JVM
 * — which ignores {@code HTTP_PROXY} — by the base's {@code JAVA_TOOL_OPTIONS} (the properties it
 * also puts in {@code GRADLE_OPTS}), {@code curl} by its {@code HTTP_PROXY}, Maven by its root-owned
 * settings. The image (committed context {@code jvm-proxy-egress/}) is the base plus a grafted JDK,
 * Maven and a probe, and sets no proxy of its own (design D7 of add-sandbox-base-image).
 *
 * <p>The box's internal network has no route out, so an answer from the allowlisted target (a local
 * HTTP server on the bridge, standing in for Maven Central) or the guard's own 403 for a
 * non-allowlisted host proves the request transited the guard. Docker- and guard-image-gated.
 */
@IgnoreIf({
    !GuardImageAvailability.available()
})
class JvmProxyEgressE2ESpec extends Specification implements BareGitRepoFixture {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')
    static final String IMAGE = 'gnomish-sandbox-jvm-proxy-test:latest'
    static final String MAVEN_GOAL = 'mvn -B -Dmaven.repo.local=/tmp/m2 org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get'

    @TempDir
    Path tempDir

    private final InstantSource clock = new VirtualClock()
    private final DockerCli docker = new DockerCli()

    private String key
    private String targetName
    private String targetIp
    private ContainerTaskExecutionEnvironment env
    private EgressGuard guard

    def setupSpec() {
        Path context = Path.of(JvmProxyEgressE2ESpec.getResource('/jvm-proxy-egress/Dockerfile').toURI()).parent
        SandboxBaseImage.buildFrom(IMAGE, context, 'Dockerfile').success()
    }

    def setup() {
        key = 'jvmproxy-' + System.nanoTime()
        and_startAllowedTargetOnBridge()
        and_materializeBoxOnInternalNetwork()
        guard = new EgressGuard(docker, key, GuardImageAvailability.IMAGE, [targetIp], tempDir.resolve('guard-cfg'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        guard.ensureRunning()
    }

    def cleanup() {
        env?.dispose()
        if (guard != null) {
            docker.run(GuardCommands.removeGuard(key))
        }
        if (targetName != null) {
            docker.run(DockerCommands.removeContainer(targetName))
        }
    }

    // Scenario: sandbox/sandbox-egress / Gradle build flows through the guard
    def "FR7: a JVM ignoring proxy env vars still reaches an allowlisted host through the guard"() {
        when: 'the box JVM GETs the allowlisted target — routed only by the baked -Dhttp.proxyHost'
        def out = probe("http://${targetIp}/")

        then: 'it reaches the allowlisted registry through the guard'
        out.contains('HTTP 200')
    }

    // FR10 of add-sandbox-base-image: curl-class tools ride the base's inherited proxy spelling.
    def "FR10: curl with no proxy option of its own reaches the allowlisted host through the base's proxy"() {
        when: 'the box curls the allowlisted target, naming no proxy: only the inherited HTTP_PROXY routes it'
        def status = curlStatus("http://${targetIp}/")

        then: 'the internal network has no direct route, so the 200 came through the guard'
        status == '200'
    }

    // FR10 of add-sandbox-base-image: Maven rides the base's root-owned settings — their proxy and
    //     their mirror, selected by GNOMISH_MAVEN_MIRROR. With the JVM and curl features above, the
    //     three tool families of the scenario are each shown routed through the guard.
    // Scenario: sandbox/sandbox-egress / Plumbing is inherited, not copied
    def "FR10: Maven with no proxy option of its own reaches an allowlisted mirror through the guard"() {
        when: 'Maven fetches a plugin from a mirror on the allowlisted target, then from a denied host'
        def allowed = maven("http://${targetIp}/maven2")
        def denied = maven("http://${EnvironmentSelfCheck.DENIED_PROBE_HOST}/maven2")

        then: 'the target answered 404 for the absent plugin through the guard; the guard denied the other'
        allowed.contains("Could not find artifact")
        allowed.contains("gnomish-mirror (http://${targetIp}/maven2)")
        denied.contains('403')
    }

    // Scenario: sandbox/sandbox-egress / Non-allowlisted host is denied
    def "FR7: the same JVM is denied a non-allowlisted host by the guard's own 403"() {
        when: 'the box JVM GETs a non-allowlisted host'
        def out = probe("http://${EnvironmentSelfCheck.DENIED_PROBE_HOST}/")

        then: 'the guard denies it — proof the JVM traffic transits the guard, not a direct route'
        out.contains('HTTP 403')
    }

    /** The in-box JVM probe's output for {@code url}: a {@code HTTP <status>} line once the guard answers. */
    private String probe(String url) {
        untilAnswered([
            'java',
            '/gnomish-probe/Probe.java',
            url
        ]) {
            it.contains('HTTP ')
        }
    }

    /** Maven's output for a plugin goal resolved through a mirror at {@code mirror}, once it got an answer. */
    private String maven(String mirror) {
        untilAnswered([
            'env',
            "GNOMISH_MAVEN_MIRROR=${mirror}".toString()
        ] + MAVEN_GOAL.split(' ').toList()) {
            it.contains('Could not find artifact') || it.contains('403')
        }
    }

    /** The status curl reports for {@code url}; {@code 000} while it reached nothing. */
    private String curlStatus(String url) {
        untilAnswered('curl -s -o /dev/null --max-time 10 -w %{http_code}'.split(' ').toList() + url) {
            it ==~ /[1-5]\d\d/
        }
    }

    /**
     * Runs {@code argv} in the box until its merged, stripped output is an answer. The just-created
     * guard may not be listening yet, so a refused proxy connection is retried — the same readiness
     * budget the production self-check uses.
     */
    private String untilAnswered(List<String> argv, Closure<Boolean> answered) {
        String out = ''
        for (int attempt = 0; attempt < 20 && !answered(out); attempt++) {
            if (attempt> 0) {
                Thread.sleep(500)
            }
            def handle = env.exec(new ExecCommand(argv, [:], null, StderrPolicy.MERGED))
            out = new String(handle.output().stream().readAllBytes(), StandardCharsets.UTF_8).strip()
            handle.waitForExit()
        }
        out
    }

    /** A local HTTP target on the default bridge — the allowlisted destination the guard can reach. */
    private void and_startAllowedTargetOnBridge() {
        targetName = 'gnomish-jvm-target-' + key
        def run = docker.run([
            'run',
            '-d',
            '--name',
            targetName,
            '--user',
            '0',
            EgressProbeImage.ensureBuilt(),
            'sh',
            '-c',
            'echo ok > /tmp/index.html && exec httpd -f -p 80 -h /tmp'
        ])
        assert run.ok(): "target container failed to start: ${run.stderr()}"
        def inspect = docker.run([
            'inspect',
            '-f',
            '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',
            targetName
        ])
        assert inspect.ok()
        targetIp = inspect.stdout().forParsing().strip()
        assert targetIp ==~ /(\d{1,3}\.){3}\d{1,3}/: "target container has no bridge IP: '${targetIp}'"
    }

    private void and_materializeBoxOnInternalNetwork() {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/jvm-proxy')
        env = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, IMAGE, LIMITS, clock),
                key, new BoxGitLink(source, new ContainerHarvestFetch(new GitProcessRunner(), source)), ChildEnvAllowlist.none(), 'proj-1')
        env.materialize('task/jvm-proxy', null)
    }
}
