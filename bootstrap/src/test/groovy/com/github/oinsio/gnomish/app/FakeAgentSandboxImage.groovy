package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary

/**
 * Builds (once per JVM and scenario) the container-mode E2E sandbox image: the
 * factory image contract of task 9.1 — alpine, git, curl, a non-root {@code
 * gnome} user (uid 1000) owning {@code /gnomish/**} — plus the fake agent and
 * its scenario library baked at {@code /opt/gnomish-fake} with the scenario
 * pinned via image {@code ENV} (the container child-env base is empty by D6,
 * so the image is the only channel that can carry {@code
 * GNOMISH_FAKE_SCENARIO} into rounds without an operator passthrough).
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
     * fix-operator-blockers, container mode). The file is harvested into the snapshot commit, so
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
        File context = FakeAgentBinary.rootDir().toFile()
        String dockerfile = """
            FROM alpine:3
            RUN apk add --no-cache git curl \\
             && adduser -D -u 1000 gnome \\
             && mkdir -p /gnomish/work /gnomish/scratch \\
             && chown -R gnome:gnome /gnomish
            COPY fake-agent.sh /opt/gnomish-fake/fake-agent.sh
            COPY scenarios /opt/gnomish-fake/scenarios
            RUN chmod -R a+rX /opt/gnomish-fake && chmod a+x /opt/gnomish-fake/fake-agent.sh${extraSteps}
            ENV GNOMISH_FAKE_SCENARIO=${scenario}
            USER gnome
        """.stripIndent()
        def build = new ProcessBuilder('docker', 'build', '-t', tag, '-f', '-', context.absolutePath)
                .redirectErrorStream(true)
                .start()
        build.outputStream.withWriter('UTF-8') { it << dockerfile }
        String output = new String(build.inputStream.readAllBytes(), 'UTF-8')
        assert build.waitFor() == 0: "docker build of ${tag} failed:\n${output}"
        tag
    }
}
