package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.testfixtures.standin.StandIn

/**
 * Builds (once per JVM and scenario) the container-mode E2E sandbox image: the
 * sandbox base image, which carries the factory's image contract — git at the
 * factory's floor, curl, a non-root {@code gnome} user (uid 1000) owning
 * {@code /gnomish/**}, the contract label — built by {@link SandboxBaseImage}
 * (design D7 of add-sandbox-base-image), plus the fake agent and its scenario
 * library baked at {@code /opt/gnomish-fake} with the scenario pinned via image
 * {@code ENV} (the container child-env base is empty by D6, so the image is the
 * only channel that can carry {@code GNOMISH_FAKE_SCENARIO} into rounds without
 * an operator passthrough).
 */
class FakeAgentSandboxImage {

    /** The in-box agent binary path — the {@code factory.agent-cli-binary} value container specs use. */
    static final String BINARY = '/opt/gnomish-fake/fake-agent.sh'

    /**
     * The in-box argv-checking wrapper of {@link #ensureBuiltCheckingArgv} — the {@code
     * factory.agent-cli-binary} value of a container spec that inspects the launched argv.
     */
    static final String ARGV_CHECKING_BINARY = '/opt/gnomish-fake/argv-checking-agent.sh'

    /** The working-copy file an executor round's argv is captured into, harvested with the snapshot. */
    static final String EXECUTOR_ARGV_CAPTURE = 'fake-agent-argv.txt'

    /**
     * The in-box credential-capturing wrapper of {@link #ensureBuiltCapturingCredentials} — the
     * {@code factory.agent-cli-binary} value of a container spec that inspects a round's agent
     * credentials.
     */
    static final String CREDENTIAL_CAPTURING_BINARY = '/opt/gnomish-fake/env-capturing-agent.sh'

    /** The working-copy file a round's agent credentials are written into, harvested with the snapshot. */
    static final String CREDENTIAL_CAPTURE = 'fake-agent-credentials.txt'

    private static final Set<String> built = [] as Set

    /** Builds the image for {@code scenario} if this JVM has not yet; returns the tag. */
    static synchronized String ensureBuilt(String scenario) {
        build("gnomish-sandbox-e2e-${scenario}:latest", scenario, '')
    }

    /**
     * Builds (once per JVM and judge model) an image whose {@link #ARGV_CHECKING_BINARY} reads
     * back, in the box, the argv each role was launched with (M1 of fix-operator-blockers,
     * container mode). The two roles need two channels, because a judge vote runs in a fresh box
     * that is disposed before the run returns:
     * <ul>
     *   <li>an executor round plays {@code plain-round} and captures its argv into {@link
     *       #EXECUTOR_ARGV_CAPTURE} in its working copy, so the file is harvested into the
     *       snapshot commit and the spec reads it back with git;</li>
     *   <li>a judge vote ({@code --model judgeModel}) checks its own argv: with {@code
     *       --permission-mode dontAsk}, {@code --strict-mcp-config} and no {@code
     *       bypassPermissions} it plays {@code judge-verdict-pass}, otherwise it returns a failing
     *       verdict whose finding quotes the argv — so the stage passes only if the vote launched
     *       correctly.</li>
     * </ul>
     * The wrapper is the test resource {@code fake-agent-argv-check/argv-checking-agent.sh},
     * written into the image base64-encoded so it needs no file in the build context.
     */
    static synchronized String ensureBuiltCheckingArgv(String judgeModel) {
        String encoded = encodedResource('/fake-agent-argv-check/argv-checking-agent.sh')
        String judgeModelEncoded = "${judgeModel}\n".bytes.encodeBase64().toString()
        build("gnomish-sandbox-e2e-argv-check-${judgeModel}:latest", 'plain-round', """
            RUN echo '${encoded}' | base64 -d > ${ARGV_CHECKING_BINARY} \\
             && echo '${judgeModelEncoded}' | base64 -d > /opt/gnomish-fake/judge-model \\
             && chmod a+rx ${ARGV_CHECKING_BINARY} && chmod a+r /opt/gnomish-fake/judge-model""")
    }

    /**
     * Builds (once per JVM) an image whose {@link #CREDENTIAL_CAPTURING_BINARY} writes the values
     * of {@code CLAUDE_CODE_OAUTH_TOKEN} and {@code ANTHROPIC_API_KEY} it received into {@link
     * #CREDENTIAL_CAPTURE} in its working copy, then plays {@code plain-round} (FR5, NFR-S3 of
     * fix-operator-blockers, container mode), and whether the two passthrough probe names of
     * {@code ContainerModeAgentCredentialE2ESpec} are set (FR2 of fix-docker-exec-env-argv). The
     * file is harvested into the snapshot commit, so
     * the spec reads back what the agent round's in-box environment held. The wrapper is the test
     * resource {@code fake-agent-env-capture/env-capturing-agent.sh}, embedded the same way as the
     * argv-checking one.
     */
    static synchronized String ensureBuiltCapturingCredentials() {
        String encoded = encodedResource('/fake-agent-env-capture/env-capturing-agent.sh')
        build('gnomish-sandbox-e2e-credential-capture:latest', 'plain-round', """
            RUN echo '${encoded}' | base64 -d > ${CREDENTIAL_CAPTURING_BINARY} \\
             && chmod a+rx ${CREDENTIAL_CAPTURING_BINARY}""")
    }

    /** The in-box verdict program of {@link #ensureBuiltWithVerdictProgram}, run as {@code sh <path> <operation>}. */
    static final String VERDICT_PROGRAM = '/opt/gnomish-fake/judge-bot.sh'

    /**
     * Builds (once per JVM) the {@code plain-round} image with a pollable verdict program at {@link
     * #VERDICT_PROGRAM} (task 5.3 of migrate-verify-to-executor-contract): it describes as the
     * stand-in reference executor does and keeps its run in its keys, which name the {@code HEAD} each
     * request's box stood on — the test resource {@code fake-verdict-program/judge-bot.sh}, embedded
     * as the argv-checking one is. A container spec declares it as a law's program.
     */
    static synchronized String ensureBuiltWithVerdictProgram() {
        String encoded = encodedResource('/fake-verdict-program/judge-bot.sh')
        String describe = StandIn.data('executor-describe#reference').bytes.encodeBase64().toString()
        build('gnomish-sandbox-e2e-verdict-program:latest', 'plain-round', """
            RUN echo '${encoded}' | base64 -d > ${VERDICT_PROGRAM} \\
             && echo '${describe}' | base64 -d > /opt/gnomish-fake/judge-describe.json \\
             && chmod a+r ${VERDICT_PROGRAM} /opt/gnomish-fake/judge-describe.json""")
    }

    /** The in-box SonarQube-shaped verdict program of {@link #ensureBuiltWithSonarGate}, run as {@code sh <path> <operation>}. */
    static final String SONAR_GATE_PROGRAM = '/opt/gnomish-sonar/sonar-gate.sh'

    /**
     * Builds (once per JVM and server address) the {@code plain-round} image with the SonarQube-shaped
     * reference verdict program at {@link #SONAR_GATE_PROGRAM} (task 7.1 of
     * migrate-verify-to-executor-contract, M3), installed for the server at {@code host}: the test
     * resource {@code fake-verdict-program/sonar-gate.sh}, embedded as the judge-bot one is, and the
     * address in {@code /opt/gnomish-sonar/host} — an installation's configuration, which its describe
     * declares as its egress. A packaged-jar spec declares it as a law's program.
     */
    static synchronized String ensureBuiltWithSonarGate(String host) {
        String encoded = encodedResource('/fake-verdict-program/sonar-gate.sh')
        String address = "${host}\n".bytes.encodeBase64().toString()
        build("gnomish-sandbox-e2e-sonar-gate-${host.replace('.', '-')}:latest", 'plain-round', """
            RUN mkdir -p /opt/gnomish-sonar && echo '${encoded}' | base64 -d > ${SONAR_GATE_PROGRAM} \\
             && echo '${address}' | base64 -d > /opt/gnomish-sonar/host \\
             && chmod -R a+rX /opt/gnomish-sonar""")
    }

    /** A test resource's bytes, base64-encoded for an image {@code RUN} step with no build-context file. */
    private static String encodedResource(String resource) {
        FakeAgentSandboxImage.getResourceAsStream(resource).withCloseable {
            it.readAllBytes()
        }.encodeBase64().toString()
    }

    private static String build(String tag, String scenario, String extraSteps) {
        if (!built.add(tag)) {
            return tag
        }
        SandboxBaseImage.buildFromText(tag, FakeAgentBinary.rootDir(), """
            FROM \${BASE}
            USER root
            COPY fake-agent.sh /opt/gnomish-fake/fake-agent.sh
            COPY scenarios /opt/gnomish-fake/scenarios
            RUN chmod -R a+rX /opt/gnomish-fake && chmod a+x /opt/gnomish-fake/fake-agent.sh${extraSteps}
            ENV GNOMISH_FAKE_SCENARIO=${scenario}
            USER gnome
        """)
        tag
    }
}
