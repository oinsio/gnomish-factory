package com.github.oinsio.gnomish.e2e.sandboximage

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.sandbox.environment.ImageContract
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR1, FR4, FR6, FR11, NFR-S1, NFR-P1 of add-sandbox-base-image: the sandbox base image built from
 * {@code sandbox-image/Dockerfile} by the E2E base fixture carries the factory's image contract —
 * its labels, its proxy environment, its git, its size — and nothing an operator adds (task 2.1,
 * verified here by task 2.2). The image's configuration is read once, through
 * {@code docker image inspect}; what only a running container shows is asked in one.
 */
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class BaseImageContractSpec extends Specification {

    private static final long SIZE_TARGET_BYTES = 150_000_000L

    @Shared
    String base

    @Shared
    Map config

    @Shared
    ImageContract contract = ImageContract.current()

    def setupSpec() {
        base = SandboxBaseImage.ensureBuilt()
        config = new ObjectMapper().readValue(
                TestDockerCommand.succeed([
                    'image',
                    'inspect',
                    '-f',
                    '{{json .Config}}',
                    base
                ]), Map)
    }

    // FR4, FR6: the image half of the one-source claim — the label the base was built with is the
    //     version the factory built from the same file requires (the factory half is pinned by
    //     ImageContractSourceSpec).
    // Scenario: sandbox/sandbox-base-image / One edit changes both sides
    def "FR4, FR6: the base carries the contract label the factory requires and the OCI labels"() {
        expect:
        labels['io.gnomish.image-contract'] == contract.contractVersion().toString()
        labels['org.opencontainers.image.version'] == SandboxBaseImage.IMAGE_VERSION
        labels['org.opencontainers.image.revision'] == SandboxBaseImage.IMAGE_REVISION
        labels['org.opencontainers.image.source'] == 'https://github.com/oinsio/gnomish-factory'
    }

    // FR1: the final user is gnome, uid 1000, and the work root is the working directory.
    def "FR1: the base runs as gnome with uid 1000 in the work root"() {
        expect:
        config.User == 'gnome'
        config.WorkingDir == '/gnomish/work'
        TestDockerCommand.shell(base, 'gnome', 'id -u; id -un').success().readLines() == ['1000', 'gnome']
    }

    // FR1: every tool family reads the guard's alias and port from the image's own environment.
    // Scenario: sandbox/sandbox-base-image / Every tool family finds the proxy
    def "FR1: curl-class tools, JVM tools and Maven find the guard's proxy"() {
        given:
        def proxy = "http://${contract.guardAlias()}:${contract.guardPort()}".toString()
        def jvm = ['http', 'https'].collect {
            "-D${it}.proxyHost=${contract.guardAlias()} -D${it}.proxyPort=${contract.guardPort()}"
        }.join(' ')

        expect:
        [
            'HTTP_PROXY',
            'HTTPS_PROXY',
            'http_proxy',
            'https_proxy'
        ].every {
            environment[it] == proxy
        }
        environment.JAVA_TOOL_OPTIONS == jvm
        environment.GRADLE_OPTS == jvm
        environment.MAVEN_ARGS == '-s /etc/gnomish/maven/settings.xml'

        and: 'the settings Maven is pointed at name the guard, and only root may change them'
        def settings = TestDockerCommand.shell(base, 'gnome',
                'stat -c "%U %a" /etc/gnomish/maven/settings.xml; cat /etc/gnomish/maven/settings.xml').success()
        settings.readLines().first() == 'root 644'
        settings.contains("<host>${contract.guardAlias()}</host><port>${contract.guardPort()}</port>")
    }

    // NFR-S1: no agent CLI, by binary or by package.
    // Scenario: sandbox/sandbox-base-image / No agent CLI in the published image
    def "NFR-S1: the base holds no agent CLI"() {
        expect:
        absent('for t in claude codex gemini; do command -v $t; done; ls /usr/local/lib/node_modules /usr/lib/node_modules; '
                + 'dpkg -l | grep -i -e claude -e anthropic -e codex')
    }

    // NFR-S1: no project toolchain and no credential, in the environment or on disk.
    // Scenario: sandbox/sandbox-base-image / No toolchain or credential in the published image
    def "NFR-S1: the base holds no JDK, no Node and no credential"() {
        expect:
        absent('for t in java javac keytool node npm; do command -v $t; done; ls -d /opt/java /usr/lib/jvm')
        environment.keySet().findAll {
            it =~ /(?i)token|secret|password|credential|api_?key/
        } == [] as Set
        absent('find / -xdev \\( -name .credentials.json -o -name .git-credentials -o -name .netrc '
                + '-o -name "id_rsa*" -o -name "id_ed25519*" \\) -print')
    }

    // FR11, NFR-P1: git is built in a stage the image does not carry, so no build tool remains and
    //     the image stays under the size target.
    // Scenario: sandbox/sandbox-base-image / The published base stays under the size target
    def "FR11, NFR-P1: the base is under 150 MB and holds no compiler, header or build tool"() {
        when:
        def size = TestDockerCommand.succeed([
            'image',
            'inspect',
            '-f',
            '{{.Size}}',
            base
        ]).strip() as long

        then:
        size <SIZE_TARGET_BYTES
        absent('for t in gcc cc c++ clang make ld as autoconf; do command -v $t; done; ls /usr/include/*.h /usr/lib/gcc')
    }

    // FR11: the base's git is at or above the factory's floor (the seed-clone floor check that
    //     judges a box is moved onto the same floor by task 4.2, which pins that scenario).
    def "FR11: the base's git meets the factory's git floor"() {
        when:
        def line = TestDockerCommand.shell(base, 'gnome', 'git --version').success().strip()
        def version = GitVersion.parse(line)

        then:
        version.isPresent()
        !version.get().isBelow(GitVersion.FLOOR)
        TestDockerCommand.shell(base, 'gnome', 'for t in git curl; do command -v $t; done').success().readLines() ==
                [
                    '/opt/git/bin/git',
                    '/usr/bin/curl'
                ]
    }

    private Map<String, String> getLabels() {
        config.Labels as Map<String, String>
    }

    private Map<String, String> getEnvironment() {
        (config.Env as List<String>).collectEntries { String entry ->
            int split = entry.indexOf('=')
            [(entry.substring(0, split)): entry.substring(split + 1)]
        }
    }

    /** Whether {@code probe}, run as root in the base, printed nothing to its standard output. */
    private boolean absent(String probe) {
        TestDockerCommand.shell(base, 'root', "{ ${probe}; } 2>/dev/null").output.strip().isEmpty()
    }
}
