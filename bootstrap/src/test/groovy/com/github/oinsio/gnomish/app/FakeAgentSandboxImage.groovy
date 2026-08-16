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

    private static final Set<String> built = [] as Set

    /** Builds the image for {@code scenario} if this JVM has not yet; returns the tag. */
    static synchronized String ensureBuilt(String scenario) {
        String tag = "gnomish-sandbox-e2e-${scenario}:latest"
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
            RUN chmod -R a+rX /opt/gnomish-fake && chmod a+x /opt/gnomish-fake/fake-agent.sh
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
