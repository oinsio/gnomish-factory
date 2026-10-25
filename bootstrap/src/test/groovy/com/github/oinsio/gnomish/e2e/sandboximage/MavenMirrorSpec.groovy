package com.github.oinsio.gnomish.e2e.sandboximage

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR1, NFR-S1 of add-sandbox-base-image, design D12: the Maven mirror is an operator parameter. The
 * base's root-owned {@code settings.xml} names {@code ${env.GNOMISH_MAVEN_MIRROR}} and the base sets
 * that variable to Maven Central; an operator image selects its own mirror with one {@code ENV} line
 * and never rewrites the settings. The operator image ({@code sandbox-base-image/Dockerfile.maven})
 * grafts Maven and its JDK from the official Maven image in a stage of its own (design D13).
 *
 * <p>No request is made: every container runs with no network, and the Maven run is {@code
 * --offline} on a plugin goal the empty local repository does not hold, so Maven refuses before any
 * transfer — and its refusal names the mirror it resolved from the root-owned settings.
 */
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class MavenMirrorSpec extends Specification {

    private static final String MAVEN_TAG = 'gnomish-sandbox-base-maven:e2e'
    private static final String SETTINGS = '/etc/gnomish/maven/settings.xml'
    private static final String CENTRAL = 'https://repo.maven.apache.org/maven2'
    private static final String OPERATOR_MIRROR = 'http://mirror.invalid/maven2'
    private static final String MIRROR_LINE =
    '<mirror><id>gnomish-mirror</id><mirrorOf>central</mirrorOf><url>${env.GNOMISH_MAVEN_MIRROR}</url></mirror>'

    @Shared
    String base

    def setupSpec() {
        base = SandboxBaseImage.ensureBuilt()
        Path context = SandboxBaseImage.repoRoot().resolve('bootstrap/src/test/resources/sandbox-base-image')
        SandboxBaseImage.buildFrom(MAVEN_TAG, context, 'Dockerfile.maven').success()
    }

    def cleanupSpec() {
        TestDockerCommand.run([
            'image',
            'rm',
            '-f',
            MAVEN_TAG
        ])
    }

    // FR1, design D12: the base bakes no mirror URL into its settings; it names the variable, and
    //     the variable's default is Maven Central.
    def "FR1: the base's settings name the mirror variable, and the base sets it to Maven Central"() {
        when:
        def settings = TestDockerCommand.shell(base, 'gnome', "cat ${SETTINGS}").success()

        then:
        settings.contains(MIRROR_LINE)
        !settings.contains(CENTRAL)
        environment(base).GNOMISH_MAVEN_MIRROR == CENTRAL
    }

    // FR1, design D12: Maven in an operator image that set only GNOMISH_MAVEN_MIRROR resolves its
    //     mirror from the base's root-owned settings — Maven's own interpolation, no request made.
    // Scenario: sandbox/sandbox-egress / An operator selects a Maven mirror
    def "FR1: Maven in an operator image names the operator's mirror through the base's settings"() {
        when: 'Maven runs offline on a plugin goal the empty local repository does not hold'
        def run = TestDockerCommand.shell(MAVEN_TAG, 'gnome',
                'mvn -B --offline -Dmaven.repo.local=/tmp/m2 org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get')

        then: 'it refuses, naming the mirror the settings resolved from the operator\'s variable'
        run.exitCode != 0
        run.output.contains("gnomish-mirror (${OPERATOR_MIRROR})")
        !run.output.contains(CENTRAL)
        environment(MAVEN_TAG).GNOMISH_MAVEN_MIRROR == OPERATOR_MIRROR
    }

    // NFR-S1, design D12: selecting a mirror rewrote nothing — the settings file the operator image
    //     carries is the base's, still root-owned and not writable by the gnome.
    def "NFR-S1: the operator image's settings file is still the base's, root-owned"() {
        when:
        def stat = { String image ->
            TestDockerCommand.shell(image, 'gnome', "stat -c '%U %a' ${SETTINGS}; sha256sum ${SETTINGS}").success()
        }

        then:
        stat(MAVEN_TAG).readLines().first() == 'root 644'
        stat(MAVEN_TAG) == stat(base)
        TestDockerCommand.shell(MAVEN_TAG, 'gnome', "test -w ${SETTINGS}").exitCode != 0
    }

    private static Map<String, String> environment(String image) {
        def env = new ObjectMapper().readValue(TestDockerCommand.succeed([
            'image',
            'inspect',
            '-f',
            '{{json .Config.Env}}',
            image
        ]), List) as List<String>
        env.collectEntries { String entry ->
            int split = entry.indexOf('=')
            [(entry.substring(0, split)): entry.substring(split + 1)]
        }
    }
}
